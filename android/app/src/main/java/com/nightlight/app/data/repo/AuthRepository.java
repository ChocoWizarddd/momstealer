package com.nightlight.app.data.repo;

import android.content.Context;

import com.google.gson.Gson;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.OtpDtos;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.util.AccountPrefs;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.ErrorMapper;
import com.nightlight.app.util.TokenStore;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Registers the device with the backend exactly once and stores the bearer
 * token securely. Music features work without auth; user data requires it.
 */
public final class AuthRepository {

    private final Context app;
    private final NightLightApi api;
    private volatile boolean registering;

    public AuthRepository(Context context) {
        this.app = context.getApplicationContext();
        this.api = ApiClient.nightLightApi(app);
    }

    public boolean isAuthenticated() {
        return TokenStore.hasToken();
    }

    /** True when a signed-in user has completed language + category onboarding. */
    public boolean isOnboarded() {
        return AccountPrefs.isOnboarded(app);
    }

    /**
     * Requests a 6-digit email verification code. On success, the code is
     * delivered by the backend (Resend in production; dev channel locally); it
     * is NEVER surfaced through this app. {@code onError} receives a friendly
     * message or null on success.
     */
    public void requestOtp(String email,
                           final Callback2 onSuccess,
                           final Callback1<String> onError) {
        api.requestOtp(new OtpDtos.RequestOtpRequest(email))
                .enqueue(new Callback<ApiResponse<OtpDtos.RequestOtpResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call,
                                           Response<ApiResponse<OtpDtos.RequestOtpResponse>> response) {
                        ApiResponse<OtpDtos.RequestOtpResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /**
     * Verifies the code. On success the returned session token is stored and
     * the email is persisted with the account. The user record is created
     * server-side on first verification.
     */
    public void verifyOtp(String email, String otp,
                          final Callback2 onSuccess,
                          final Callback1<String> onError) {
        api.verifyOtp(new OtpDtos.VerifyOtpRequest(email, otp))
                .enqueue(new Callback<ApiResponse<OtpDtos.VerifyOtpResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.VerifyOtpResponse>> call,
                                           Response<ApiResponse<OtpDtos.VerifyOtpResponse>> response) {
                        ApiResponse<OtpDtos.VerifyOtpResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.token != null) {
                            TokenStore.setToken(body.data.token);
                            AccountPrefs.setEmail(app, email);
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.VerifyOtpResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Best-effort server-side sync of onboarding selections (never blocks UI). */
    public void savePreferences(List<String> languages, List<String> categories) {
        if (!TokenStore.hasToken()) {
            return;
        }
        api.savePreferences(new OtpDtos.SavePreferencesRequest(languages, categories))
                .enqueue(new Callback<ApiResponse<Object>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                        // Best-effort: local onboarding is authoritative for the UI.
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                        // Best-effort: ignore.
                    }
                });
    }

    /** Clears the local session and account state after revoking the token server-side. */
    public void logout() {
        final String token = TokenStore.peekToken();
        if (TokenStore.hasToken()) {
            // Fire-and-forget revoke; local state is cleared regardless.
            api.logout().enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
                @Override
                public void onResponse(retrofit2.Call<ApiResponse<Object>> call,
                                       retrofit2.Response<ApiResponse<Object>> response) {
                }

                @Override
                public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                }
            });
        }
        TokenStore.clearToken();
        AccountPrefs.clear(app);
        AccountPrefs.clearProfile(app);
        new com.nightlight.app.data.api.GoogleSignInHelper(app).signOut(app);
    }

    // ---- Email + password auth (Firebase identity, NightLight session) ----

    private final com.nightlight.app.data.api.FirebaseAuthClient firebase =
            new com.nightlight.app.data.api.FirebaseAuthClient();

    /**
     * Google Sign-In: exchanges the Google ID token for a NightLight session.
     * The backend verifies the token against Google's public keys, requires a
     * verified email, and links the account by email (same mailbox from
     * Google or password = same NightLight account).
     */
    public void loginWithGoogle(String googleIdToken,
                                final Callback2 onSuccess,
                                final Callback1<String> onError) {
        storeGoogleProfile(googleIdToken);
        exchangeAndFinish(googleIdToken, null, onSuccess, onError);
    }

    /**
     * Captures the Google display name + photo for the account page. The ID
     * token payload is decoded WITHOUT signature verification — display
     * metadata only; identity and session still come from the verified
     * backend exchange.
     */
    private void storeGoogleProfile(String idToken) {
        try {
            if (idToken == null) return;
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return;
            byte[] raw = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            org.json.JSONObject claims = new org.json.JSONObject(new String(raw, "UTF-8"));
            String name = claims.optString("name", null);
            String picture = claims.optString("picture", null);
            if ((name != null && !name.isEmpty()) || (picture != null && !picture.isEmpty())) {
                AccountPrefs.setProfile(app, name, picture);
            }
        } catch (Exception ignored) {
        }
    }

    /** In-memory pending registration state (app-scoped repository). */
    private String pendingEmail;
    private String pendingPassword;
    private String pendingFirebaseRefresh;
    /** True once a verification link was actually sent for the pending account. */
    private boolean pendingVerificationSent;

    /**
     * Creates the account with Firebase AND sends the verification link
     * immediately (REST signUp never sends mail by itself — skipping this is
     * why users waited on a "Check Email" screen with nothing arriving). NO
     * session is issued until the mailbox is verified and exchanged.
     */
    public void registerPassword(String email, String password,
                                 final Callback2 onSuccess,
                                 final Callback1<String> onError) {
        firebase.signUp(email, password, (data, err) -> {
            if (err != null) {
                if (err.contains("already exists")) {
                    // Friendly nudge: existing users should log in instead.
                    onError.run(err);
                } else {
                    onError.run(err);
                }
                return;
            }
            pendingEmail = email;
            pendingPassword = password;
            pendingFirebaseRefresh = data == null ? null : data.optString("refreshToken", null);
            pendingVerificationSent = false;
            AccountPrefs.setEmail(app, email);
            String idToken = data == null ? null : data.optString("idToken", null);
            if (idToken == null || idToken.isEmpty()) {
                // Account exists but we have no session to send the link with;
                // the user can tap Resend on the next screen.
                onSuccess.run();
                return;
            }
            firebase.sendVerificationEmail(idToken, (d, err2) -> {
                pendingVerificationSent = err2 == null;
                if (err2 != null) {
                    android.util.Log.w("NightLightAuth", "initial verification send failed");
                }
                onSuccess.run();
            });
        });
    }

    /** Whether the pending account was actually sent a verification link. */
    public boolean isVerificationEmailSent() {
        return pendingVerificationSent;
    }

    /** Re-sends the Firebase verification email for the pending registration. */
    public void resendVerificationEmail(final Callback2 onSuccess, final Callback1<String> onError) {
        if (pendingFirebaseRefresh == null) {
            onError.run("Start by creating the account first.");
            return;
        }
        firebase.refreshIdToken(pendingFirebaseRefresh, (tok, err) -> {
            if (err != null) {
                onError.run(err);
                return;
            }
            String idToken = tok == null ? null : tok.optString("id_token", null);
            if (idToken == null) {
                onError.run("Could not refresh the session. Please log in again.");
                return;
            }
            // sendOobCode with VERIFY_EMAIL requires an ID token.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                firebase.sendVerificationEmail(idToken, (d, err2) -> {
                    if (err2 != null) {
                        onError.run(err2);
                    } else {
                        pendingVerificationSent = true;
                        onSuccess.run();
                    }
                });
            });
        });
    }

    /**
     * Called when the user taps "I've verified" (or poll timer fires):
     * re-authenticates and exchanges. On success the NightLight session is
     * stored exactly like password login.
     */
    public void completeRegistration(final Callback2 onSuccess, final Callback1<String> onError) {
        if (pendingEmail == null || pendingPassword == null) {
            onError.run("Please log in to continue.");
            return;
        }
        // signIn re-issues an ID token that now carries email_verified=true.
        firebase.signIn(pendingEmail, pendingPassword, (signInData, signInErr) -> {
            if (signInErr != null) {
                onError.run(signInErr);
                return;
            }
            String idToken = signInData == null ? null : signInData.optString("idToken", null);
            if (idToken == null) {
                onError.run("Sign-in succeeded but the session could not be created. Try again.");
                return;
            }
            exchangeAndFinish(idToken, pendingEmail, onSuccess, verifiedError -> {
                // Distinguish "email not verified yet" from other errors.
                if (verifiedError != null && verifiedError.contains("Verify your email")) {
                    onError.run("Not verified yet - tap the link in your email, then try again.");
                } else {
                    onError.run(verifiedError);
                }
            });
        });
    }
    /**
     * Best-effort refresh of the backend-authoritative account record. Silent by
     * design: any failure — or any field the backend does not serve — leaves the
     * cached local profile untouched.
     *
     * <p>{@code GET auth/me} currently returns only {@code id}, {@code stats}
     * and {@code preferences}; the identity fields (email, username,
     * displayName, photoUrl) are populated from the Firebase exchange response
     * instead. Every field is therefore applied only when the server actually
     * sent a value, so a sparse response can never blank the cached profile.
     */
    public void refreshProfile() {
        if (!TokenStore.hasToken()) return;
        api.getProfile().enqueue(new Callback<ApiResponse<UserDtos.ProfileDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.ProfileDto>> call,
                                   Response<ApiResponse<UserDtos.ProfileDto>> response) {
                ApiResponse<UserDtos.ProfileDto> body = response.body();
                if (!response.isSuccessful() || body == null || !body.success
                        || body.data == null) {
                    return;
                }
                UserDtos.ProfileDto p = body.data;
                if (isPresent(p.email)) {
                    AccountPrefs.setEmail(app, p.email.trim());
                }
                if (isPresent(p.username)) {
                    AccountPrefs.setUsername(app, p.username.trim());
                }
                // Only overwrite the cached name/photo when the server sent at
                // least one of them, otherwise a sparse payload erases them.
                if (isPresent(p.displayName) || isPresent(p.photoUrl)) {
                    AccountPrefs.setProfile(app,
                            isPresent(p.displayName) ? p.displayName : null,
                            isPresent(p.photoUrl) ? p.photoUrl : null);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.ProfileDto>> call, Throwable t) {
                // Silent: cached profile stands.
            }
        });
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** Logs in with email + password via Firebase, then exchanges for a session. */
    public void loginPassword(String email, String password,
                              final Callback2 onSuccess,
                              final Callback1<String> onError) {
        firebase.signIn(email, password, (data, err) -> {
            if (err != null) {
                onError.run(err);
                return;
            }
            String idToken = data == null ? null : data.optString("idToken", null);
            if (idToken == null) {
                onError.run("Sign-in succeeded but the session could not be created. Try again.");
                return;
            }
            exchangeAndFinish(idToken, email, onSuccess, onError);
        });
    }

    /**
     * Swaps a Firebase ID token for the NightLight session via the backend
     * (server verifies the token against Google's public keys and enforces a
     * verified mailbox), then stores the session exactly like OTP login.
     */
    private void exchangeAndFinish(String idToken, String email,
                                   final Callback2 onSuccess,
                                   final Callback1<String> onError) {
        api.firebaseExchange(new OtpDtos.FirebaseExchangeRequest(idToken))
                .enqueue(new Callback<ApiResponse<OtpDtos.FirebaseExchangeResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.FirebaseExchangeResponse>> call,
                                           Response<ApiResponse<OtpDtos.FirebaseExchangeResponse>> response) {
                        // Safe diagnostic: never write server error bodies, tokens,
                        // or account data to Logcat in a release build.
                        if (!response.isSuccessful() && com.nightlight.app.BuildConfig.DEBUG) {
                            android.util.Log.w("NightLightAuth", "exchange HTTP " + response.code());
                        }
                        ApiResponse<OtpDtos.FirebaseExchangeResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.token != null) {
                            TokenStore.setToken(body.data.token);
                            // Pin the profile to an email: explicit param first
                            // (password/OTP flows), else the server user record
                            // (Google flow) — 1 gmail = 1 username + 1 genre set.
                            String knownEmail = email;
                            if ((knownEmail == null || knownEmail.isEmpty())
                                    && body.data.user != null) {
                                knownEmail = body.data.user.email;
                            }
                            if (knownEmail != null && !knownEmail.trim().isEmpty()) {
                                AccountPrefs.setEmail(app, knownEmail.trim());
                            }
                            // Backend username is authoritative (one permanent
                            // name per Firebase UID): stored, never prompted.
                            if (body.data.user != null) {
                                String backendName = body.data.user.username;
                                if (backendName != null && !backendName.trim().isEmpty()) {
                                    AccountPrefs.setUsername(app, backendName.trim());
                                }
                                AccountPrefs.setProfile(app, body.data.user.displayName,
                                        body.data.user.photoUrl);
                            }
                            pendingEmail = null;
                            pendingPassword = null;
                            pendingFirebaseRefresh = null;
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.FirebaseExchangeResponse>> call, Throwable t) {
                        android.util.Log.e("NightLightAuth", "exchange network failure", t);
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /**
     * Password recovery: Google emails a reset link; the user completes the
     * reset in the browser and returns to the app to log in.
     */
    public void forgotPassword(String email,
                               final Callback2 onSuccess,
                               final Callback1<String> onError) {
        firebase.sendPasswordReset(email, (data, err) -> {
            if (err != null) {
                onError.run(err);
            } else {
                onSuccess.run();
            }
        });
    }

    /** Exchanges the emailed reset code for a one-time reset token. */
    public void verifyResetOtp(String email, String otp,
                               final Callback1<String> onSuccess,
                               final Callback1<String> onError) {
        api.resetVerify(new OtpDtos.ResetVerifyRequest(email, otp))
                .enqueue(new Callback<ApiResponse<OtpDtos.ResetVerifyResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.ResetVerifyResponse>> call,
                                           Response<ApiResponse<OtpDtos.ResetVerifyResponse>> response) {
                        ApiResponse<OtpDtos.ResetVerifyResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.resetToken != null) {
                            onSuccess.run(body.data.resetToken);
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.ResetVerifyResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Completes the reset with the one-time token; returns the user to Login. */
    public void resetPassword(String email, String resetToken, String newPassword,
                              final Callback2 onSuccess,
                              final Callback1<String> onError) {
        api.resetPassword(new OtpDtos.ResetPasswordRequest(email, resetToken, newPassword))
                .enqueue(new Callback<ApiResponse<Object>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Object>> call,
                                           Response<ApiResponse<Object>> response) {
                        ApiResponse<Object> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    // ---- Guest mode ----

    /** Enters the app without any server identity. Playlists stay local-only. */
    public void continueAsGuest() {
        AccountPrefs.setGuest(app, true);
    }

    public boolean isGuest() {
        return AccountPrefs.isGuest(app);
    }

    /** Called after a successful signup that converts a guest account. */
    public void markConvertedFromGuest() {
        AccountPrefs.clearGuest(app);
    }

    private String friendlyError(Response<?> response, ApiResponse<?> body) {
        ApiResponse<?> parsed = body;
        if (parsed == null && response.errorBody() != null) {
            try {
                parsed = new Gson().fromJson(response.errorBody().string(), ApiResponse.class);
            } catch (Exception ignored) {
                // Fall through to the code-based mapping.
            }
        }
        if (parsed != null && parsed.code != null) {
            String code = parsed.code;
            if ("OTP_INVALID".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_invalid);
            }
            if ("OTP_EXPIRED".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_expired);
            }
            if ("OTP_TOO_MANY_ATTEMPTS".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_too_many);
            }
            if ("RESEND_COOLDOWN".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_cooldown);
            }
            if ("EMAIL_SERVICE_NOT_CONFIGURED".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_not_configured);
            }
            if ("INVALID_EMAIL".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_email);
            }
        }
        return ErrorMapper.forApiResponse(app, parsed);
    }

    /** Success callback (no payload). */
    public interface Callback2 {
        void run();
    }

    /** Single-argument callback (a friendly message). */
    public interface Callback1<T> {
        void run(T value);
    }

    /**
     * Ensures a token exists. Never blocks the caller: the result arrives on
     * the main thread. The deviceId is stable per install.
     */
    public void ensureAuthenticated(final Runnable onReady, final Runnable onFailure) {
        if (TokenStore.hasToken()) {
            onReady.run();
            return;
        }
        if (registering) {
            // A registration is already in flight; retry shortly.
            AppExecutors.onMain(() -> AppExecutors.get().main().execute(() -> ensureAuthenticated(onReady, onFailure)));
            return;
        }

        registering = true;
        String deviceId = TokenStore.getDeviceId();
        Requests.RegisterRequest body = new Requests.RegisterRequest(deviceId, "1.0.0");

        api.register(body).enqueue(new Callback<ApiResponse<UserDtos.AuthDataDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.AuthDataDto>> call,
                                   Response<ApiResponse<UserDtos.AuthDataDto>> response) {
                registering = false;
                ApiResponse<UserDtos.AuthDataDto> body = response.body();
                if (response.isSuccessful() && body != null && body.success && body.data != null
                        && body.data.token != null) {
                    TokenStore.setToken(body.data.token);
                    onReady.run();
                } else {
                    onFailure.run();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.AuthDataDto>> call, Throwable t) {
                registering = false;
                onFailure.run();
            }
        });
    }
}
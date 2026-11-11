package com.nightlight.app.data.api;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.CredentialManager;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Google Sign-In via Credential Manager.
 *
 * Returns a Google ID token (aud = GOOGLE_WEB_CLIENT_ID, iss =
 * accounts.google.com). The app exchanges it at /auth/firebase/exchange,
 * which routes Google-issuer tokens to the Google verifier - identity is
 * Google's, the session is NightLight's.
 *
 * The serverClientId is the OAuth *Web* client ID from Firebase (public).
 * Firebase must have the app's package + release SHA-1 registered or Google
 * returns DEVELOPER_ERROR.
 */
public final class GoogleSignInHelper {

    private static final String TAG = "NightLightAuth";
    /** Injected at app startup from BuildConfig.GOOGLE_WEB_CLIENT_ID. */
    public static volatile String serverClientId;

    /**
     * All callbacks are marshaled to the main thread (same pattern as
     * FirebaseAuthClient). CredentialManager delivers results on the
     * executor thread, but every consumer touches UI — missing this =
     * CalledFromWrongThread crash.
     */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final CredentialManager manager;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public GoogleSignInHelper(Context context) {
        this.manager = CredentialManager.create(context.getApplicationContext());
        logClientIdSuffix();
    }

    private void logClientIdSuffix() {
        if (serverClientId != null && !serverClientId.isEmpty()) {
            // Show last 30 chars for verification
            String suffix = serverClientId.length() > 30
                    ? serverClientId.substring(serverClientId.length() - 30)
                    : serverClientId;
            Log.i(TAG, "googleClientIdPresent=true");
            Log.i(TAG, "googleClientIdSuffix=" + suffix);
        } else {
            Log.w(TAG, "googleClientIdPresent=false");
        }
    }

    public interface Callback {
        /** idToken carries the Google ID token; error carries a friendly message (null = user cancelled). */
        void onResult(String idToken, String error);
    }

    public void signIn(Activity activity, Callback cb) {
        if (serverClientId == null || serverClientId.isEmpty()) {
            Log.w(TAG, "Google sign-in blocked: GOOGLE_WEB_CLIENT_ID empty in this build");
            cb.onResult(null, "Google sign-in is not configured in this build yet.");
            return;
        }
        Log.i(TAG, "GoogleAuth: request=authorized_accounts");
        attemptSignInWithGoogleId(activity, cb, true);
    }

    public void signInWithGoogleButton(Activity activity, Callback cb) {
        if (serverClientId == null || serverClientId.isEmpty()) {
            Log.w(TAG, "Google sign-in blocked: GOOGLE_WEB_CLIENT_ID empty in this build");
            cb.onResult(null, "Google sign-in is not configured in this build yet.");
            return;
        }
        Log.i(TAG, "GoogleAuth: request=sign_in_with_google_button");
        attemptSignInWithGoogleButton(activity, cb);
    }

    private void attemptSignInWithGoogleId(Activity activity, Callback cb, boolean filterByAuthorizedAccounts) {
        Log.i(TAG, "GoogleAuth: authorized_accounts_flow_start filterByAuthorizedAccounts=" + filterByAuthorizedAccounts);
        Log.i(TAG, "GoogleAuth: activity_finishing=" + activity.isFinishing() + " activity_destroyed=" + activity.isDestroyed());
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        Log.i(TAG, "GoogleAuth: getCredential_start");
        manager.getCredentialAsync(activity, request, null, executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Log.i(TAG, "GoogleAuth: getCredential_returned=true");
                        try {
                            if (result.getCredential() == null) {
                                Log.w(TAG, "GoogleAuth: credential_class=null");
                                MAIN.post(() -> cb.onResult(null, "No credential returned. Try again."));
                                return;
                            }
                            String credentialClass = result.getCredential().getClass().getName();
                            Log.i(TAG, "GoogleAuth: credential_class=" + credentialClass);
                            if (result.getCredential() instanceof CustomCredential) {
                                CustomCredential customCred = (CustomCredential) result.getCredential();
                                String credType = customCred.getType();
                                Log.i(TAG, "GoogleAuth: credential_type=" + credType);
                                if (GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credType)) {
                                    GoogleIdTokenCredential cred = GoogleIdTokenCredential.createFrom(customCred.getData());
                                    Log.i(TAG, "GoogleAuth: google_id_token_parsed=true");
                                    String idToken = cred.getIdToken();
                                    boolean tokenPresent = idToken != null && !idToken.isEmpty();
                                    Log.i(TAG, "GoogleAuth: id_token_present=" + tokenPresent);
                                    if (tokenPresent) {
                                        Log.i(TAG, "GoogleAuth: login_callback_main=true");
                                        MAIN.post(() -> cb.onResult(idToken, null));
                                    } else {
                                        Log.w(TAG, "GoogleAuth: id_token_empty");
                                        MAIN.post(() -> cb.onResult(null, "Google sign-in returned empty token. Try again."));
                                    }
                                    return;
                                } else {
                                    Log.w(TAG, "GoogleAuth: unexpected_credential_type=" + credType);
                                }
                            } else {
                                Log.w(TAG, "GoogleAuth: unexpected_credential_class=" + credentialClass);
                            }
                            MAIN.post(() -> cb.onResult(null, "Unexpected sign-in credential. Try again."));
                        } catch (Exception e) {
                            String exClass = e.getClass().getName();
                            String exMsg = e.getMessage() != null ? e.getMessage() : "null";
                            Log.w(TAG, "GoogleAuth: credential_parse_error class=" + exClass + " msg=" + exMsg);
                            MAIN.post(() -> cb.onResult(null, "Could not read the Google sign-in result. Try again."));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        String name = e.getClass().getSimpleName();
                        String detail = e.getMessage() != null ? e.getMessage() : "null";
                        Log.w(TAG, "GoogleAuth: getCredential_error=" + name);
                        Log.w(TAG, "GoogleAuth: getCredential_error_message=" + detail);
                        if (e instanceof GetCredentialCancellationException) {
                            MAIN.post(() -> cb.onResult(null, null)); // user closed the sheet
                            return;
                        }
                        if (filterByAuthorizedAccounts && (e instanceof NoCredentialException || name.equals("NoCredentialException"))) {
                            Log.i(TAG, "GoogleAuth: retry=all_accounts");
                            attemptSignInWithGoogleId(activity, cb, false);
                            return;
                        }
                        final String msg;
                        if (e instanceof NoCredentialException || name.equals("NoCredentialException")) {
                            msg = "No Google account found. Please add a Google account in Settings.";
                        } else if (name.contains("ProviderNotFound") || name.contains("UnableToGetService")
                                || detail.contains("DEVELOPER") || detail.contains("developer")) {
                            msg = "Google sign-in is not linked to this app yet. Check Firebase SHA-1.";
                        } else if (name.contains("GetCredentialProviderConfigurationException")) {
                            msg = "Google Play Services needs an update. Please update and try again.";
                        } else {
                            msg = "Google sign-in failed (" + name + "). Try again.";
                        }
                        MAIN.post(() -> cb.onResult(null, msg));
                    }
                });
    }

    private void attemptSignInWithGoogleButton(Activity activity, Callback cb) {
        Log.i(TAG, "GoogleAuth: button_flow_start");
        Log.i(TAG, "GoogleAuth: activity_finishing=" + activity.isFinishing() + " activity_destroyed=" + activity.isDestroyed());
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(serverClientId)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        Log.i(TAG, "GoogleAuth: getCredential_start");
        manager.getCredentialAsync(activity, request, null, executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Log.i(TAG, "GoogleAuth: getCredential_returned=true");
                        try {
                            if (result.getCredential() == null) {
                                Log.w(TAG, "GoogleAuth: credential_class=null");
                                MAIN.post(() -> cb.onResult(null, "No credential returned. Try again."));
                                return;
                            }
                            String credentialClass = result.getCredential().getClass().getName();
                            Log.i(TAG, "GoogleAuth: credential_class=" + credentialClass);
                            if (result.getCredential() instanceof CustomCredential) {
                                CustomCredential customCred = (CustomCredential) result.getCredential();
                                String credType = customCred.getType();
                                Log.i(TAG, "GoogleAuth: credential_type=" + credType);
                                if (GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credType)) {
                                    GoogleIdTokenCredential cred = GoogleIdTokenCredential.createFrom(customCred.getData());
                                    Log.i(TAG, "GoogleAuth: google_id_token_parsed=true");
                                    String idToken = cred.getIdToken();
                                    boolean tokenPresent = idToken != null && !idToken.isEmpty();
                                    Log.i(TAG, "GoogleAuth: id_token_present=" + tokenPresent);
                                    if (tokenPresent) {
                                        Log.i(TAG, "GoogleAuth: login_callback_main=true");
                                        MAIN.post(() -> cb.onResult(idToken, null));
                                    } else {
                                        Log.w(TAG, "GoogleAuth: id_token_empty");
                                        MAIN.post(() -> cb.onResult(null, "Google sign-in returned empty token. Try again."));
                                    }
                                    return;
                                } else {
                                    Log.w(TAG, "GoogleAuth: unexpected_credential_type=" + credType);
                                }
                            } else {
                                Log.w(TAG, "GoogleAuth: unexpected_credential_class=" + credentialClass);
                            }
                            MAIN.post(() -> cb.onResult(null, "Unexpected sign-in credential. Try again."));
                        } catch (Exception e) {
                            String exClass = e.getClass().getName();
                            String exMsg = e.getMessage() != null ? e.getMessage() : "null";
                            Log.w(TAG, "GoogleAuth: credential_parse_error class=" + exClass + " msg=" + exMsg);
                            MAIN.post(() -> cb.onResult(null, "Could not read the Google sign-in result. Try again."));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        String name = e.getClass().getSimpleName();
                        String detail = e.getMessage() != null ? e.getMessage() : "null";
                        Log.w(TAG, "GoogleAuth: getCredential_error=" + name);
                        Log.w(TAG, "GoogleAuth: getCredential_error_message=" + detail);
                        if (e instanceof GetCredentialCancellationException) {
                            MAIN.post(() -> cb.onResult(null, null));
                            return;
                        }
                        final String msg;
                        if (e instanceof NoCredentialException || name.equals("NoCredentialException")) {
                            msg = "No Google account found. Please add a Google account in Settings.";
                        } else if (name.contains("ProviderNotFound") || name.contains("UnableToGetService")
                                || detail.contains("DEVELOPER") || detail.contains("developer")) {
                            msg = "Google sign-in is not linked to this app yet. Check Firebase SHA-1.";
                        } else if (name.contains("GetCredentialProviderConfigurationException")) {
                            msg = "Google Play Services needs an update. Please update and try again.";
                        } else {
                            msg = "Google sign-in failed (" + name + "). Try again.";
                        }
                        MAIN.post(() -> cb.onResult(null, msg));
                    }
                });
    }

    /** Clears the Credential Manager state after logout. */
    public void signOut(Context context) {
        try {
            manager.clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
                    Runnable::run, new CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override
                        public void onResult(Void result) {
                        }

                        @Override
                        public void onError(androidx.credentials.exceptions.ClearCredentialException e) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}

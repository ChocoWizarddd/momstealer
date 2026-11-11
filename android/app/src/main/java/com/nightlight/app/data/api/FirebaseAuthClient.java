package com.nightlight.app.data.api;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal Firebase Authentication REST client (Identity Toolkit).
 *
 * Why REST instead of the Firebase SDK: the SDK requires a google-services.json
 * and the Gradle plugin; the REST API needs only the Web API key, keeping the
 * build config simple. Firebase's free Spark plan serves unlimited users.
 *
 * Security: the Web API key is a public identifier (same class as the API base
 * URL), not a secret. All sensitive operations are protected by Firebase
 * rules/server-side verification. No passwords or tokens are ever logged.
 */
public final class FirebaseAuthClient {

    private static final String TAG = "NightLightAuth";
    private static final String BASE = "https://identitytoolkit.googleapis.com/v1/accounts:";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * All callbacks are marshaled to the main thread here (single
     * chokepoint): OkHttp enqueues on worker threads, but every consumer
     * touches UI/session state. Missing this = CalledFromWrongThread crash.
     */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Set from NightLightApp at startup (BuildConfig/gradle property). */
    public static volatile String apiKey;

    private final OkHttpClient http;

    public FirebaseAuthClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public interface Result {
        void onDone(JSONObject data, String errorMessage);
    }

    /** Creates the account. Email verification mail is sent by Google. */
    public void signUp(String email, String password, Result cb) {
        call("signUp", email, password, cb);
    }

    /** Signs in and returns idToken/refreshToken/localId. */
    public void signIn(String email, String password, Result cb) {
        call("signInWithPassword", email, password, cb);
    }

    /** Sends the verification email for the signed-in account (Google delivers). */
    public void sendVerificationEmail(String idToken, FirebaseAuthClient.Result cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("requestType", "VERIFY_EMAIL");
            body.put("idToken", idToken);
        } catch (Exception ignored) {
        }
        post("sendOobCode", body, cb);
    }

    /** Sends the password-reset email (Firebase free tier, delivered by Google). */
    public void sendPasswordReset(String email, Result cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("requestType", "PASSWORD_RESET");
            body.put("email", email);
        } catch (Exception ignored) {
        }
        post("sendOobCode", body, (data, err) -> cb.onDone(data, err));
    }

    /** Refreshes an ID token with the long-lived refresh token. */
    public void refreshIdToken(String refreshToken, Result cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", refreshToken);
        } catch (Exception ignored) {
        }
        RequestBody rb = RequestBody.create(body.toString(), JSON);
        Request req = new Request.Builder()
                .url("https://securetoken.googleapis.com/v1/token?key=" + apiKey)
                .post(rb)
                .build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                MAIN.post(() -> cb.onDone(null, "Can't reach the sign-in service. Check your connection."));
            }

            @Override
            public void onResponse(okhttp3.Call call, Response resp) throws IOException {
                finish(resp, cb);
            }
        });
    }

    /** Fetches account profile fields (emailVerified etc.) for an ID token. */
    public void lookup(String idToken, Result cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("idToken", idToken);
        } catch (Exception ignored) {
        }
        post("lookup", body, cb);
    }

    private void call(String op, String email, String password, Result cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
            body.put("returnSecureToken", true);
        } catch (Exception ignored) {
        }
        post(op, body, cb);
    }

    private void post(String op, JSONObject body, Result cb) {
        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "Firebase auth blocked: FIREBASE_API_KEY empty in this build (op=" + op + ")");
            cb.onDone(null, "Sign-in is not configured in this build yet.");
            return;
        }
        RequestBody rb = RequestBody.create(body.toString(), JSON);
        Request req = new Request.Builder()
                .url(BASE + op + "?key=" + apiKey)
                .post(rb)
                .build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                MAIN.post(() -> cb.onDone(null, "Can't reach the sign-in service. Check your connection."));
            }

            @Override
            public void onResponse(okhttp3.Call call, Response resp) throws IOException {
                finish(resp, cb);
            }
        });
    }

    private void finish(Response resp, Result cb) throws IOException {
        String text = "";
        try (Response r = resp) {
            text = r.body() != null ? r.body().string() : "";
        }
        final String body = text;
        final int code;
        try {
            code = resp.code();
        } catch (Exception e) {
            MAIN.post(() -> cb.onDone(null, "Couldn't complete sign-in. Please try again."));
            return;
        }
        MAIN.post(() -> {
            JSONObject json = null;
            try {
                json = new JSONObject(body);
            } catch (Exception ignored) {
            }
            if (code >= 200 && code < 300 && json != null) {
                cb.onDone(json, null);
                return;
            }
            cb.onDone(null, friendlyFirebaseError(json, code));
        });
    }

    /** Maps Firebase error codes to polished user messages (never raw JSON). */
    private static String friendlyFirebaseError(JSONObject err, int httpCode) {
        String code = "";
        if (err != null && err.has("error")) {
            try {
                code = err.getJSONObject("error").getString("message");
            } catch (Exception ignored) {
            }
        }
        String lower = code.toLowerCase();
        if (lower.contains("email_exists"))
            return "An account with this email already exists. Try logging in.";
        if (lower.contains("invalid_login_credentials")
                || lower.contains("invalid_password")
                || lower.contains("email_not_found"))
            return "Email or password is incorrect.";
        if (lower.contains("weak_password"))
            return "Password must be at least 6 characters.";
        if (lower.contains("too_many_attempts"))
            return "Too many attempts. Please wait a minute and try again.";
        if (lower.contains("invalid_email"))
            return "Enter a valid email address.";
        if (lower.contains("operation_not_allowed"))
            return "Email sign-in is not enabled for this app yet.";
        if (lower.contains("api key") || lower.contains("api_key")
                || lower.contains("project_not"))
            return "Sign-in is not configured in this build yet. Check your Firebase API key.";
        if (httpCode >= 500)
            return "Sign-in service is having trouble. Try again shortly.";
        Log.w(TAG, "Firebase error: " + code + " (" + httpCode + ")");
        return "Couldn't complete sign-in. Please try again.";
    }
}

package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.SecureRandom;

/**
 * Stores the NightLight bearer token in EncryptedSharedPreferences (AES-GCM
 * keyed by a master key in the Android Keystore). The token is never logged.
 */
public final class TokenStore {

    private static final String PREFS = "nightlight_auth";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE_ID = "device_id";

    private static volatile SharedPreferences prefs;
    private static volatile String token;
    private static volatile String deviceId;

    private TokenStore() {
    }

    public static void init(Context context) {
        if (prefs != null) {
            return;
        }
        synchronized (TokenStore.class) {
            if (prefs == null) {
                try {
                    MasterKey masterKey = new MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();
                    prefs = EncryptedSharedPreferences.create(
                            context,
                            PREFS,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                } catch (Exception e) {
                    // Keystore unavailable (rare): degrade to plain prefs in a
                    // private file rather than crashing at startup.
                    prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                }
                token = prefs.getString(KEY_TOKEN, null);
                deviceId = prefs.getString(KEY_DEVICE_ID, null);
                if (deviceId == null) {
                    deviceId = generateDeviceId(context);
                    prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
                }
            }
        }
    }

    public static String getToken() {
        return token;
    }

    /**
     * Reads the persisted token without mutating in-memory state. Used at
     * logout so the exact active token can be revoked server-side.
     */
    public static String peekToken() {
        return token != null ? token : (prefs != null ? prefs.getString(KEY_TOKEN, null) : null);
    }

    public static boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    public static void setToken(String value) {
        token = value;
        if (prefs != null) {
            prefs.edit().putString(KEY_TOKEN, value).apply();
        }
    }

    public static void clearToken() {
        token = null;
        if (prefs != null) {
            prefs.edit().remove(KEY_TOKEN).apply();
        }
    }

    public static String getDeviceId() {
        return deviceId;
    }

    private static String generateDeviceId(Context context) {
        // Prefer a random value so no hardware identifier leaves the device.
        byte[] random = new byte[20];
        new SecureRandom().nextBytes(random);
        return "nl-" + Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
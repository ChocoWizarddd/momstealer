package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.nightlight.app.reco.TasteProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-account persistence for the long-term taste profile (§6, §22-local).
 * Keyed by normalized gmail; guests and signed-out sessions are memory-only
 * (session profile) and never touch this store.
 *
 * MongoDB sync is a separate contract (see architecture report §L): the flat
 * string map from TasteProfile.toMap() is the sync payload.
 */
public final class TastePrefs {

    private static final String PREFS = "nightlight_taste";

    private TastePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Storage scope for the current identity, or null when session-only. */
    public static String scopeKey(Context context) {
        String email = AccountPrefs.email(context);
        if (email == null || email.trim().isEmpty()) return null;
        if (AccountPrefs.isGuest(context)) return null;
        return "t::" + email.trim().toLowerCase();
    }

    public static void loadInto(Context context, String scope, TasteProfile taste) {
        if (scope == null || taste == null) return;
        SharedPreferences p = prefs(context);
        Map<String, String> m = new HashMap<>();
        for (String k : new String[]{"genres", "artists", "languages", "moods",
                "liked", "completed", "playtimeMs", "completedTracks"}) {
            String v = p.getString(scope + "/" + k, null);
            if (v != null) m.put(k, v);
        }
        if (!m.isEmpty()) taste.fromMap(m);
    }

    public static void save(Context context, String scope, TasteProfile taste) {
        if (scope == null || taste == null) return;
        Map<String, String> m = taste.toMap();
        SharedPreferences.Editor ed = prefs(context).edit();
        for (Map.Entry<String, String> e : m.entrySet()) {
            ed.putString(scope + "/" + e.getKey(), e.getValue());
        }
        ed.apply();
    }
}

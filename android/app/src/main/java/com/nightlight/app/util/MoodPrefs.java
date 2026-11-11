package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Explicit listening mood. The user picks one from the Home mood selector; it
 * becomes the strongest context signal for Smart Shuffle and Home suggestions.
 * The mood is session-scoped: it decays after {@link #DECAY_MS} so the app
 * never locks a listener into one mood forever.
 */
public final class MoodPrefs {

    /** Time after which an explicit mood is treated as stale/inactive. */
    public static final long DECAY_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private static final String PREFS = "nightlight_ui";
    private static final String KEY_MOOD = "explicit_mood";
    private static final String KEY_AT = "explicit_mood_at";

    private MoodPrefs() {
    }

    public static void set(Context context, String mood) {
        long now = System.currentTimeMillis();
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MOOD, mood)
                .putLong(KEY_AT, now)
                .apply();
    }

    /** The active explicit mood, or null when none is set / it has decayed. */
    public static String active(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mood = prefs.getString(KEY_MOOD, null);
        long at = prefs.getLong(KEY_AT, 0L);
        if (mood == null || at <= 0) {
            return null;
        }
        if (System.currentTimeMillis() - at > DECAY_MS) {
            clear(context);
            return null;
        }
        return mood;
    }

    public static void clear(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MOOD)
                .remove(KEY_AT)
                .apply();
    }
}
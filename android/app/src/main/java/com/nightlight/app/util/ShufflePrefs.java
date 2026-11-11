package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shuffle behavior preferences. Three states, cycled OFF -&gt; SMART -&gt;
 * NORMAL -&gt; OFF:
 *
 * <ul>
 *   <li>{@code smart}  — default ("Shuffle ON"). Context-aware, diversity-aware
 *                         recommendation session built from what you play.</li>
 *   <li>{@code normal} — plain Fisher-Yates random permutation of the playback
 *                         context (see {@code ShuffleOrderFactory.plainOrder}):
 *                         every track plays once per cycle, no radio top-ups.</li>
 *   <li>{@code off}    — ("Queue ON" / Shuffle OFF). Plays the queue in listed
 *                         order, no mixing, no top-ups.</li>
 * </ul>
 *
 * Discovery preference tunes how much Smart Shuffle wanders outside the current
 * mood family:
 * <ul>
 *   <li>{@code familiar}  — strongly favor matching mood/genre/liked music.</li>
 *   <li>{@code balanced}  — default: mix of relevance and discovery.</li>
 *   <li>{@code discovery} — allow more adjacent genres and lower-confidence picks.</li>
 * </ul>
 */
public final class ShufflePrefs {

    public static final String SMART = "smart";
    public static final String NORMAL = "normal";
    public static final String OFF = "off";
    public static final String DEFAULT_MODE = SMART;

    public static final String FAMILIAR = "familiar";
    public static final String BALANCED = "balanced";
    public static final String DISCOVERY = "discovery";
    public static final String DEFAULT_DISCOVERY = BALANCED;

    private static final String PREFS = "nightlight_ui";
    private static final String KEY_MODE = "shuffle_mode";
    private static final String KEY_DISCOVERY = "shuffle_discovery";

    private ShufflePrefs() {
    }

    public static String mode(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, DEFAULT_MODE);
        return isMode(mode) ? mode : DEFAULT_MODE;
    }

    public static void setMode(Context context, String mode) {
        if (!isMode(mode)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode)
                .apply();
    }

    /** Cycles OFF -&gt; SMART -&gt; NORMAL -&gt; OFF (shuffle switch on Now Playing). */
    public static String cycle(Context context) {
        String current = mode(context);
        String next = OFF.equals(current) ? SMART : SMART.equals(current) ? NORMAL : OFF;
        setMode(context, next);
        return next;
    }

    public static boolean isSmart(Context context) {
        return SMART.equals(mode(context));
    }

    public static boolean isNormal(Context context) {
        return NORMAL.equals(mode(context));
    }

    public static boolean isOff(Context context) {
        return OFF.equals(mode(context));
    }

    public static String discovery(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String d = prefs.getString(KEY_DISCOVERY, DEFAULT_DISCOVERY);
        return isDiscovery(d) ? d : DEFAULT_DISCOVERY;
    }

    public static void setDiscovery(Context context, String d) {
        if (!isDiscovery(d)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DISCOVERY, d)
                .apply();
    }

    /** Discovery ratio: fraction of picks that deliberately wander. */
    public static double discoveryRatio(Context context) {
        String d = discovery(context);
        if (FAMILIAR.equals(d)) {
            return 0.0;
        }
        if (DISCOVERY.equals(d)) {
            return 0.35;
        }
        return 0.18; // balanced
    }

    private static boolean isMode(String mode) {
        return SMART.equals(mode) || NORMAL.equals(mode) || OFF.equals(mode);
    }

    private static boolean isDiscovery(String d) {
        return FAMILIAR.equals(d) || BALANCED.equals(d) || DISCOVERY.equals(d);
    }
}
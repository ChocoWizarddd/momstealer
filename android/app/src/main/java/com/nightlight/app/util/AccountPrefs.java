package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Account-level UI state: the verified email and the onboarding selections
 * (languages + categories). The bearer token itself lives in TokenStore
 * (EncryptedSharedPreferences); this holds non-secret account metadata.
 *
 * 1 gmail = 1 profile: username + onboarding (languages/categories) are
 * scoped per email address. Each new gmail is asked for a username and for
 * genres exactly once (first login); returning gmails reuse their stored
 * profile and skip both prompts. Guests (no email) keep the legacy
 * device-level behavior.
 */
public final class AccountPrefs {

    private static final String PREFS = "nightlight_account";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_LANGUAGES = "languages";
    private static final String KEY_CATEGORIES = "categories";
    private static final String KEY_GUEST = "guest";
    private static final String KEY_PENDING_CONVERSION = "pending_conversion";
    private static final String KEY_SKIP_PUSH = "skip_local_playlist_push";

    private AccountPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Normalized current email (lowercase) or null for guests / signed-out. */
    private static String currentEmail(Context context) {
        String email = prefs(context).getString(KEY_EMAIL, null);
        if (email == null) return null;
        email = email.trim().toLowerCase();
        return email.isEmpty() ? null : email;
    }

    /** Per-account key: base + normalized email. Device-level when no email. */
    private static String scopedKey(Context context, String base) {
        String email = currentEmail(context);
        return email == null ? base : base + "::" + email;
    }

    /**
     * Explicit guest flag. Guests never receive a server token, so every
     * token-gated sync path stays off; this flag additionally drives the UI
     * (account-required prompts) and splash routing.
     */
    public static void setGuest(Context context, boolean guest) {
        prefs(context).edit().putBoolean(KEY_GUEST, guest).apply();
    }

    public static boolean isGuest(Context context) {
        return prefs(context).getBoolean(KEY_GUEST, false);
    }

    public static void setEmail(Context context, String email) {
        String clean = email == null ? null : email.trim();
        String previous = currentEmail(context);
        String next = clean == null || clean.isEmpty() ? null : clean.toLowerCase();
        SharedPreferences p = prefs(context);
        if (clean == null || clean.isEmpty()) {
            p.edit().remove(KEY_EMAIL).apply();
            return;
        }
        p.edit().putString(KEY_EMAIL, clean).apply();
        // Upgrade path: pre-scoped installs kept one global profile. Attribute
        // it to this email the first time it signs in so the user is not
        // re-asked; afterwards every gmail owns its keys.
        if (next != null && !next.equals(previous)) {
            migrateLegacyTo(context, next);
        }
    }

    /** Copies legacy device-level profile keys to the given email scope once. */
    private static void migrateLegacyTo(Context context, String email) {
        SharedPreferences p = prefs(context);
        boolean hasScopedUsername = p.contains(KEY_USERNAME + "::" + email);
        boolean hasScopedOnboarded = p.contains(KEY_ONBOARDED + "::" + email);
        SharedPreferences.Editor ed = p.edit();
        boolean changed = false;
        if (!hasScopedUsername && p.contains(KEY_USERNAME)) {
            ed.putString(KEY_USERNAME + "::" + email, p.getString(KEY_USERNAME, ""));
            changed = true;
        }
        if (!hasScopedOnboarded && p.getBoolean(KEY_ONBOARDED, false)) {
            ed.putBoolean(KEY_ONBOARDED + "::" + email, true);
            Set<String> langs = p.getStringSet(KEY_LANGUAGES, null);
            Set<String> cats = p.getStringSet(KEY_CATEGORIES, null);
            if (langs != null) ed.putStringSet(KEY_LANGUAGES + "::" + email, new HashSet<>(langs));
            if (cats != null) ed.putStringSet(KEY_CATEGORIES + "::" + email, new HashSet<>(cats));
            changed = true;
        }
        if (changed) {
            ed.apply();
        }
        // Legacy globals are always dropped: from here on every gmail owns
        // its keys, so a second gmail can never inherit the first one's
        // username or genres.
        p.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_ONBOARDED)
                .remove(KEY_LANGUAGES)
                .remove(KEY_CATEGORIES)
                .apply();
    }

    public static String email(Context context) {
        return prefs(context).getString(KEY_EMAIL, null);
    }

    /**
     * Per-user display name shown in Listen Together chat. Scoped per email:
     * each gmail keeps its own username, asked once at account creation.
     * Never null after {@link #ensureUsername(Context)}.
     */
    public static void setUsername(Context context, String username) {
        String clean = username == null ? "" : username.trim();
        prefs(context).edit().putString(scopedKey(context, KEY_USERNAME), clean).apply();
    }

    public static String username(Context context) {
        SharedPreferences p = prefs(context);
        String scoped = p.getString(scopedKey(context, KEY_USERNAME), null);
        if (scoped != null) return scoped;
        // Pre-scoped installs (single global profile).
        return p.getString(KEY_USERNAME, null);
    }

    /**
     * Returns the stored username, creating a sensible default on first use.
     * Per email: a new gmail derives from its own email prefix (unique per
     * account); guests fall back to a stable device name.
     */
    public static String ensureUsername(Context context) {
        SharedPreferences p = prefs(context);
        String key = scopedKey(context, KEY_USERNAME);
        String existing = p.getString(key, null);
        if (existing == null && !key.equals(KEY_USERNAME)) {
            // Upgrade path: adopt the legacy global name for this email once.
            existing = p.getString(KEY_USERNAME, null);
        }
        if (existing != null && !existing.trim().isEmpty()) {
            return existing.trim();
        }
        String email = p.getString(KEY_EMAIL, null);
        String fallback;
        if (email != null && email.contains("@")) {
            fallback = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_.-]", "").trim();
            if (fallback.isEmpty()) fallback = "Listener";
            if (fallback.length() > 20) fallback = fallback.substring(0, 20);
        } else {
            fallback = "Guest-" + Math.abs(com.nightlight.app.util.TokenStore.getDeviceId().hashCode() % 9000 + 1000);
        }
        p.edit().putString(key, fallback).apply();
        return fallback;
    }

    public static boolean isOnboarded(Context context) {
        SharedPreferences p = prefs(context);
        String key = scopedKey(context, KEY_ONBOARDED);
        if (p.contains(key)) return p.getBoolean(key, false);
        // Pre-scoped installs (single global flag).
        return p.getBoolean(KEY_ONBOARDED, false);
    }

    public static void markOnboarded(Context context, List<String> languages, List<String> categories) {
        SharedPreferences.Editor ed = prefs(context).edit();
        String email = currentEmail(context);
        String onKey = email == null ? KEY_ONBOARDED : KEY_ONBOARDED + "::" + email;
        String langKey = email == null ? KEY_LANGUAGES : KEY_LANGUAGES + "::" + email;
        String catKey = email == null ? KEY_CATEGORIES : KEY_CATEGORIES + "::" + email;
        ed.putBoolean(onKey, true)
                .putStringSet(langKey, new HashSet<>(languages))
                .putStringSet(catKey, new HashSet<>(categories))
                .apply();
    }

    public static List<String> languages(Context context) {
        Set<String> set = prefs(context).getStringSet(scopedKey(context, KEY_LANGUAGES), null);
        if (set == null) {
            // Pre-scoped installs (single global selections).
            set = prefs(context).getStringSet(KEY_LANGUAGES, new HashSet<>());
        }
        return set == null ? List.of() : List.copyOf(set);
    }

    public static List<String> categories(Context context) {
        Set<String> set = prefs(context).getStringSet(scopedKey(context, KEY_CATEGORIES), null);
        if (set == null) {
            // Pre-scoped installs (single global selections).
            set = prefs(context).getStringSet(KEY_CATEGORIES, new HashSet<>());
        }
        return set == null ? List.of() : List.copyOf(set);
    }

    /**
     * Derives a default listening mood from the onboarded categories, used when
     * the user has not picked an explicit mood. Returns null when no mapping
     * applies so Home can fall back to its existing behavior.
     */
    public static String defaultMood(Context context) {
        List<String> categories = categories(context);
        if (categories.isEmpty()) {
            return null;
        }
        // Ordered by preference: first matching category wins.
        String[][] map = {
                {"love", "love"},
                {"romantic", "love"},
                {"chill", "chill"},
                {"lofi", "chill"},
                {"indie", "chill"},
                {"happy", "happy"},
                {"pop", "happy"},
                {"sad", "sad"},
                {"party", "party"},
                {"workout", "workout"},
                {"focus", "focus"},
                {"bollywood", "bollywood"},
                {"hip-hop", "energy"}
        };
        for (String category : categories) {
            for (String[] pair : map) {
                if (pair[0].equalsIgnoreCase(category.trim())) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    /**
     * The context mood used by Home + Smart Shuffle: an explicit mood wins;
     * otherwise onboarding categories seed a personalized default mood.
     */
    public static String effectiveMood(Context context) {
        String explicit = MoodPrefs.active(context);
        return explicit != null ? explicit : defaultMood(context);
    }

    /** Marks the account as converted from guest after a successful signup. */
    public static void clearGuest(Context context) {
        prefs(context).edit().remove(KEY_GUEST).apply();
    }

    /**
     * Set when a guest taps Create/Login from the account-required prompt, so
     * onboarding can offer to save their temporary local playlists (explicit
     * intent — guest data is never uploaded silently).
     */
    public static void setPendingGuestConversion(Context context, boolean pending) {
        prefs(context).edit().putBoolean(KEY_PENDING_CONVERSION, pending).apply();
    }

    public static boolean isPendingGuestConversion(Context context) {
        return prefs(context).getBoolean(KEY_PENDING_CONVERSION, false);
    }

    /**
     * When the user declines the save offer, their pre-existing local
     * playlists stay device-only forever; syncFromServer skips pushing them.
     */
    public static void setSkipLocalPlaylistPush(Context context, boolean skip) {
        prefs(context).edit().putBoolean(KEY_SKIP_PUSH, skip).apply();
    }

    public static boolean skipLocalPlaylistPush(Context context) {
        return prefs(context).getBoolean(KEY_SKIP_PUSH, false);
    }

    public static void clear(Context context) {
        // Logout drops the session pointer only. Per-gmail profiles
        // (username + genres, keyed by email) persist so a returning gmail
        // skips the username + genre prompts; a new gmail starts fresh.
        // Legacy device-level keys are dropped so they can never leak into
        // another account or a guest session.
        prefs(context).edit()
                .remove(KEY_EMAIL)
                .remove(KEY_USERNAME)
                .remove(KEY_ONBOARDED)
                .remove(KEY_LANGUAGES)
                .remove(KEY_CATEGORIES)
                .apply();
    }

    public static boolean hasAnyOnboarding(Context context) {
        return !languages(context).isEmpty() || !categories(context).isEmpty();
    }

    public static String photoUrl(Context context) {
        return prefs(context).getString("photo_url", null);
    }

    public static String profileName(Context context) {
        String name = prefs(context).getString("profile_name", null);
        return name != null ? name : username(context);
    }

    /**
     * Stores the display profile (Firebase/Google display name + photo URL).
     * Display-only metadata; identity stays with the email + session token.
     * Never overwrites an explicitly chosen username with a provider name.
     */
    public static void setProfile(Context context, String displayName, String photoUrl) {
        SharedPreferences.Editor ed = prefs(context).edit();
        if (displayName != null && !displayName.trim().isEmpty()
                && prefs(context).getString("profile_name", null) == null) {
            ed.putString("profile_name", displayName.trim());
        }
        if (photoUrl != null && !photoUrl.trim().isEmpty()) {
            ed.putString("photo_url", photoUrl.trim());
        }
        ed.apply();
    }

    /** Clears provider profile metadata (logout). Per-gmail username/genre keys persist. */
    public static void clearProfile(Context context) {
        prefs(context).edit().remove("profile_name").remove("photo_url").apply();
    }

    public static boolean isValidUsername(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.length() < 3 || s.length() > 20) return false;
        return s.matches("[A-Za-z0-9_.-]+");
    }

    // Referenced by unit-ish callers; keeps constants visible for logging/tests.
    public static Set<String> languageChoices() {
        return new HashSet<>(Arrays.asList(
                "Hindi", "English", "Punjabi", "Tamil", "Telugu",
                "Bengali", "Marathi", "Kannada", "Malayalam", "Gujarati",
                "Bhojpuri", "Odia"));
    }
}
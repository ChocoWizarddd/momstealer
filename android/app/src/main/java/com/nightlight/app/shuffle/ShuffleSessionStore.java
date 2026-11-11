package com.nightlight.app.shuffle;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the active session so a shuffle order survives process death, app
 * backgrounding and reinstall-free restarts.
 *
 * <p>Restoring a session is what makes "the order NEVER changes" true across
 * an app resume: resume restores the stored order and cursor instead of
 * building a new one.
 */
public final class ShuffleSessionStore {

    private static final String PREFS = "nightlight_shuffle_session";
    private static final String KEY_SESSION = "active";
    private static final String KEY_MANUAL = "manual_next";

    private static final Gson GSON = new Gson();
    private static final Type SESSION_TYPE = new TypeToken<SessionPayload>() { }.getType();

    private final SharedPreferences prefs;

    public ShuffleSessionStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(ShuffleSession session) {
        if (session == null) {
            clear();
            return;
        }
        prefs.edit()
                .putString(KEY_SESSION, toJson(session))
                .apply();
    }

    public ShuffleSession load() {
        return fromJson(prefs.getString(KEY_SESSION, null));
    }

    public void clear() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_MANUAL).apply();
    }

    public void saveManual(List<String> ids) {
        prefs.edit().putString(KEY_MANUAL, GSON.toJson(ids == null ? new ArrayList<String>() : ids)).apply();
    }

    public List<String> loadManual() {
        String raw = prefs.getString(KEY_MANUAL, null);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            List<String> out = GSON.fromJson(raw, new TypeToken<List<String>>() { }.getType());
            return out != null ? out : new ArrayList<>();
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    public static String toJson(ShuffleSession session) {
        if (session == null) return null;
        SessionPayload payload = new SessionPayload();
        payload.sessionId = session.sessionId;
        payload.seed = session.seed;
        payload.sourcePlaylistId = session.sourcePlaylistId;
        payload.smart = session.smart;
        payload.cycle = session.cycle;
        payload.createdAt = session.createdAt;
        payload.order = session.order();
        payload.playedIds = new ArrayList<>(session.playedIds());
        payload.cursor = session.cursor();
        return GSON.toJson(payload);
    }

    public static ShuffleSession fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            SessionPayload payload = GSON.fromJson(json, SESSION_TYPE);
            if (payload == null || payload.order == null || payload.order.isEmpty()) return null;
            ShuffleSession session = new ShuffleSession(payload.sessionId, payload.seed,
                    payload.sourcePlaylistId, payload.smart, payload.cycle, payload.createdAt,
                    payload.order);
            session.restore(payload.order, payload.playedIds, payload.cursor);
            return session;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class SessionPayload {
        String sessionId;
        long seed;
        String sourcePlaylistId;
        boolean smart;
        int cycle;
        long createdAt;
        List<String> order;
        List<String> playedIds;
        int cursor;
    }
}
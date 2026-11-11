package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Long-term taste aggregates: bounded, privacy-conscious (counts and EMA
 * affinities only — no raw event history, no telemetry). One instance per
 * account (keyed by normalized gmail; guests get a session-only instance
 * that is never persisted). Serializable to a flat string map so persistence
 * (SharedPreferences now, MongoDB sync later) stays transport-agnostic.
 *
 * Update rule: exponential moving average per signal —
 * affinity += alpha * (target - affinity), target 1 for positive signals,
 * 0 for negative; alpha scales with |weight| so likes move faster than
 * partials. Bounded to MAX_KEYS keys per dimension (lowest pruned).
 */
public final class TasteProfile {

    static final int MAX_KEYS = 60;
    static final double BASE_ALPHA = 0.25;

    private final Map<String, Double> genres = new HashMap<>();
    private final Map<String, Double> artists = new HashMap<>();
    private final Map<String, Double> languages = new HashMap<>();
    private final Map<String, Double> moods = new HashMap<>();
    private final Set<String> likedIds = new HashSet<>();
    private final Set<String> completedIds = new HashSet<>();
    private final Map<String, Double> skipMass = new HashMap<>();
    private long totalPlaytimeMs = 0;
    private int completedTracks = 0;

    public synchronized void update(Track track, Signal signal) {
        if (track == null || signal == null) return;
        double w = signal.weight;
        double target = w >= 0 ? 1.0 : 0.0;
        double alpha = Math.min(0.6, BASE_ALPHA * (0.4 + Math.abs(w)));
        nudge(genres, GenreHelper.genreOf(track), target, alpha);
        String artist = norm(track.artists);
        if (!artist.isEmpty()) nudge(artists, artist, target, alpha);
        String lang = lang(track.language);
        if (!lang.isEmpty()) nudge(languages, lang, target, alpha);
        String mood = ListeningContext.detectMood(track.name + " " + track.artists + " " + track.album);
        if (mood != null) nudge(moods, mood, target, alpha);
        if (track.id != null) {
            if (signal == Signal.LIKE) likedIds.add(track.id);
            if (signal == Signal.UNLIKE) likedIds.remove(track.id);
            if (signal.isSuccess()) {
                completedIds.add(track.id);
                if (completedIds.size() > 500) completedIds.remove(completedIds.iterator().next());
            }
            if (w < 0) {
                skipMass.put(track.id, Math.min(3.0, skipMass.getOrDefault(track.id, 0.0) + Math.abs(w)));
            }
            if (signal == Signal.FULL || signal == Signal.COMPLETED_80) completedTracks++;
        }
        prune();
    }

    private static void nudge(Map<String, Double> map, String key, double target, double alpha) {
        if (key == null || key.isEmpty()) return;
        double cur = map.getOrDefault(key, 0.5);
        map.put(key, cur + alpha * (target - cur));
    }

    private void prune() {
        pruneMap(genres);
        pruneMap(artists);
        pruneMap(languages);
        pruneMap(moods);
    }

    private static void pruneMap(Map<String, Double> map) {
        if (map.size() <= MAX_KEYS) return;
        List<Map.Entry<String, Double>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, (a, b) -> Double.compare(a.getValue(), b.getValue()));
        for (int i = 0; i < entries.size() - MAX_KEYS; i++) map.remove(entries.get(i).getKey());
    }

    public synchronized double genreAffinity(String genre) {
        return genre == null ? 0.5 : genres.getOrDefault(genre, 0.5);
    }

    public synchronized double artistAffinity(String artist) {
        String a = norm(artist);
        return a.isEmpty() ? 0.5 : artists.getOrDefault(a, 0.5);
    }

    public synchronized double languageAffinity(String language) {
        String l = lang(language);
        return l.isEmpty() ? 0.5 : languages.getOrDefault(l, 0.5);
    }

    public synchronized double moodAffinity(String mood) {
        return mood == null ? 0.5 : moods.getOrDefault(mood, 0.5);
    }

    public synchronized boolean isLiked(String id) {
        return id != null && likedIds.contains(id);
    }

    public synchronized boolean wasCompleted(String id) {
        return id != null && completedIds.contains(id);
    }

    public synchronized double skipMass(String id) {
        return id == null ? 0 : skipMass.getOrDefault(id, 0.0);
    }

    /** Top-N artists by affinity (favorite-artist neighborhood source). */
    public synchronized List<String> topArtists(int n) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(artists.entrySet());
        Collections.sort(entries, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, entries.size()); i++) out.add(entries.get(i).getKey());
        return out;
    }

    /** Top-N genres by affinity. */
    public synchronized List<String> topGenres(int n) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(genres.entrySet());
        Collections.sort(entries, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, entries.size()); i++) out.add(entries.get(i).getKey());
        return out;
    }

    public synchronized void addPlaytimeMs(long ms) {
        if (ms > 0) totalPlaytimeMs += ms;
    }

    public synchronized long totalPlaytimeMs() {
        return totalPlaytimeMs;
    }

    public synchronized int completedTrackCount() {
        return completedTracks;
    }

    public synchronized int likedCount() {
        return likedIds.size();
    }

    // ---- Flat-map serialization (prefs now, Mongo later) ----

    public synchronized Map<String, String> toMap() {
        Map<String, String> m = new HashMap<>();
        m.put("genres", joinDoubles(genres));
        m.put("artists", joinDoubles(artists));
        m.put("languages", joinDoubles(languages));
        m.put("moods", joinDoubles(moods));
        m.put("liked", joinStrings(likedIds, 500));
        m.put("completed", joinStrings(completedIds, 500));
        m.put("playtimeMs", Long.toString(totalPlaytimeMs));
        m.put("completedTracks", Integer.toString(completedTracks));
        return m;
    }

    public synchronized void fromMap(Map<String, String> m) {
        if (m == null) return;
        genres.clear(); artists.clear(); languages.clear(); moods.clear();
        likedIds.clear(); completedIds.clear();
        splitDoubles(m.get("genres"), genres);
        splitDoubles(m.get("artists"), artists);
        splitDoubles(m.get("languages"), languages);
        splitDoubles(m.get("moods"), moods);
        splitStrings(m.get("liked"), likedIds);
        splitStrings(m.get("completed"), completedIds);
        try { totalPlaytimeMs = Long.parseLong(m.get("playtimeMs")); } catch (Exception ignored) { }
        try { completedTracks = Integer.parseInt(m.get("completedTracks")); } catch (Exception ignored) { }
        prune();
    }

    private static String joinDoubles(Map<String, Double> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().replace(";", ",")).append('=')
                    .append(String.format(java.util.Locale.US, "%.3f", e.getValue()));
        }
        return sb.toString();
    }

    private static void splitDoubles(String s, Map<String, Double> out) {
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1)));
            } catch (Exception ignored) { }
        }
    }

    private static String joinStrings(Set<String> set, int cap) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : set) {
            if (i++ >= cap) break;
            if (sb.length() > 0) sb.append(';');
            sb.append(s.replace(";", ","));
        }
        return sb.toString();
    }

    private static void splitStrings(String s, Set<String> out) {
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            if (!part.isEmpty()) out.add(part);
        }
    }

    private static String norm(String s) {
        return TitleGuard.norm(s == null ? "" : s);
    }

    private static String lang(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

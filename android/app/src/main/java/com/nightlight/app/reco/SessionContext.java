package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolling session vibe (§1, PDF §4). Wraps the persistent {@link SessionProfile}
 * with in-generation evolution: every ACCEPTED pick (played or selected into
 * the sequence) advances the context via {@link #advance}, so the next
 * recommendation is ranked against the evolving session — never just the
 * original seed.
 *
 * Tracks: seed, previous, recent 10, artist history + run length, genre and
 * language distributions (top-2 languages + mixing flag), mood distribution,
 * rejected candidate ids, discovery/familiarity balance.
 */
public final class SessionContext {

    public static final int RECENT_CAP = 10;

    private final SessionProfile profile;
    private Track seed;
    private Track prev;
    private final Deque<Track> recent = new ArrayDeque<>();
    private final Map<String, Integer> artistCounts = new HashMap<>();
    private final Map<String, Integer> genreCounts = new HashMap<>();
    private final Map<String, Integer> languageCounts = new HashMap<>();
    private final Map<String, Integer> moodCounts = new HashMap<>();
    private final Set<String> rejectedIds = new HashSet<>();
    private int totalAdvanced = 0;

    public SessionContext(SessionProfile profile, Track seed) {
        this.profile = profile;
        this.seed = seed;
    }

    /** Independent copy for parallel sequence variants (no cross-talk). */
    public synchronized SessionContext copy() {
        SessionContext c = new SessionContext(profile, seed);
        c.prev = prev;
        c.recent.addAll(recent);
        c.artistCounts.putAll(artistCounts);
        c.genreCounts.putAll(genreCounts);
        c.languageCounts.putAll(languageCounts);
        c.moodCounts.putAll(moodCounts);
        c.rejectedIds.addAll(rejectedIds);
        c.totalAdvanced = totalAdvanced;
        return c;
    }

    /** Advances the vibe with an accepted (played or selected) track. */
    public synchronized void advance(Track track) {
        if (track == null) return;
        if (prev != null) recent.addLast(prev);
        prev = track;
        while (recent.size() > RECENT_CAP) recent.removeFirst();
        bump(artistCounts, TitleGuard.norm(track.artists));
        bump(genreCounts, GenreHelper.genreOf(track));
        bump(languageCounts, lang(track));
        String mood = ListeningContext.detectMood(
                track.name + " " + track.artists + " " + track.album);
        if (mood != null) bump(moodCounts, mood);
        totalAdvanced++;
    }

    /** Seeds the context from already-played session history (no double count). */
    public synchronized void seedFromProfile() {
        if (profile == null) return;
        List<Track> history = profile.lastTracks(RECENT_CAP);
        // Replay history oldest-first WITHOUT touching prev linkage twice:
        // set prev to the newest, rest into recent.
        recent.clear();
        artistCounts.clear();
        genreCounts.clear();
        languageCounts.clear();
        moodCounts.clear();
        totalAdvanced = 0;
        for (Track t : history) {
            if (t == null) continue;
            bump(artistCounts, TitleGuard.norm(t.artists));
            bump(genreCounts, GenreHelper.genreOf(t));
            bump(languageCounts, lang(t));
            String mood = ListeningContext.detectMood(
                    t.name + " " + t.artists + " " + t.album);
            if (mood != null) bump(moodCounts, mood);
            totalAdvanced++;
        }
        if (!history.isEmpty()) {
            prev = history.get(history.size() - 1);
            for (int i = 0; i < history.size() - 1; i++) recent.addLast(history.get(i));
        }
    }

    public synchronized void reject(String trackId, String reason) {
        if (trackId != null) rejectedIds.add(trackId);
    }

    public synchronized boolean wasRejected(String trackId) {
        return trackId != null && rejectedIds.contains(trackId);
    }

    public synchronized Track seed() {
        return seed;
    }

    public synchronized Track prev() {
        return prev;
    }

    /** Recent vibe tracks, oldest first (includes prev as newest). */
    public synchronized List<Track> recent() {
        List<Track> out = new ArrayList<>(recent);
        if (prev != null) out.add(prev);
        return out;
    }

    /** Consecutive trailing run length of the given (normalized) artist. */
    public synchronized int artistRunLength(String normalizedArtist) {
        if (normalizedArtist == null || normalizedArtist.isEmpty()) return 0;
        int run = 0;
        if (prev != null && normalizedArtist.equals(TitleGuard.norm(prev.artists))) {
            run = 1;
            List<Track> hist = new ArrayList<>(recent);
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (normalizedArtist.equals(TitleGuard.norm(hist.get(i).artists))) run++;
                else break;
            }
        }
        return run;
    }

    /** Genre distribution over the vibe window (sums to ~1). */
    public synchronized Map<String, Double> genreDistribution() {
        return distribution(genreCounts);
    }

    /** Language distribution over the vibe window. */
    public synchronized Map<String, Double> languageDistribution() {
        return distribution(languageCounts);
    }

    /** Top-2 session languages, most frequent first. */
    public synchronized List<String> topLanguages(int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(languageCounts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, entries.size()); i++) {
            if (!entries.get(i).getKey().isEmpty()) out.add(entries.get(i).getKey());
        }
        return out;
    }

    /**
     * True when the session demonstrably mixes languages (≥2 languages each
     * with ≥25% share): unexpected languages get a small penalty instead of
     * a strong one. Otherwise the session is monolingual and jumps are
     * penalized hard.
     */
    public synchronized boolean isMixingLanguages() {
        Map<String, Double> dist = languageDistribution();
        int strong = 0;
        for (double share : dist.values()) {
            if (share >= 0.25) strong++;
        }
        return strong >= 2;
    }

    /** Dominant genre family of the vibe window ("" when empty). */
    public synchronized String dominantGenre() {
        String best = "";
        int bestN = 0;
        for (Map.Entry<String, Integer> e : genreCounts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public synchronized String dominantMood() {
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : moodCounts.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public synchronized int size() {
        return totalAdvanced;
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null || key.isEmpty()) return;
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private static Map<String, Double> distribution(Map<String, Integer> counts) {
        Map<String, Double> out = new HashMap<>();
        int total = 0;
        for (int n : counts.values()) total += n;
        if (total <= 0) return out;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            out.put(e.getKey(), e.getValue() / (double) total);
        }
        return out;
    }

    private static String lang(Track t) {
        return t.language == null ? "" : t.language.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

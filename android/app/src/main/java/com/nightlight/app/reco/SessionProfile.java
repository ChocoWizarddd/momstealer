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
 * Short-term session vector: the last N played tracks plus graded outcomes.
 * Recent behavior weighs more than older behavior (recency-weighted counts).
 * Pure Java — fully unit-testable. Session-only: never persisted, guests and
 * authenticated users both get one; it dies with the process.
 */
public final class SessionProfile {

    public static final int MAX_RECENT = 10;

    private final Deque<Entry> recent = new ArrayDeque<>();
    private final Set<String> playedIds = new HashSet<>();
    private int playCount = 0;

    public static final class Entry {
        public final Track track;
        public final Signal signal;
        public final long atMs;

        Entry(Track track, Signal signal, long atMs) {
            this.track = track;
            this.signal = signal;
            this.atMs = atMs;
        }
    }

    /** Records a graded outcome for a track (also marks it played). */
    public synchronized void record(Track track, Signal signal) {
        if (track == null || track.id == null || signal == null) return;
        recent.addLast(new Entry(track, signal, System.currentTimeMillis()));
        playedIds.add(track.id);
        playCount++;
        while (recent.size() > MAX_RECENT) recent.removeFirst();
    }

    /** Records a plain play start (outcome upgraded later via {@link #record}). */
    public synchronized void recordPlay(Track track) {
        if (track == null || track.id == null) return;
        playedIds.add(track.id);
        playCount++;
    }

    public synchronized boolean hasPlayed(String trackId) {
        return trackId != null && playedIds.contains(trackId);
    }

    public synchronized int size() {
        return recent.size();
    }

    public synchronized List<Entry> recent() {
        return new ArrayList<>(recent);
    }

    /** Last up-to-n tracks, most recent last (for sequential context). */
    public synchronized List<Track> lastTracks(int n) {
        List<Track> out = new ArrayList<>();
        int skip = Math.max(0, recent.size() - n);
        int i = 0;
        for (Entry e : recent) {
            if (i++ >= skip) out.add(e.track);
        }
        return out;
    }

    /**
     * Recency-weighted affinity 0..1 for a key extractor over recent entries.
     * Position weight grows toward the newest; negative signals subtract.
     */
    private synchronized double affinity(java.util.function.Function<Track, String> keyOf, String key) {
        if (key == null || key.isEmpty() || recent.isEmpty()) return 0;
        double pos = 0, total = 0;
        int i = 0;
        for (Entry e : recent) {
            double recency = 0.5 + 0.5 * (i + 1.0) / recent.size();
            total += recency;
            String k = keyOf.apply(e.track);
            if (key.equals(k)) {
                pos += recency * (e.signal.weight >= 0 ? 1.0 : -1.0);
            }
            i++;
        }
        if (total <= 0) return 0;
        return Math.max(0.0, Math.min(1.0, 0.5 + 0.5 * (pos / total)));
    }

    public double genreAffinity(String genre) {
        return affinity(t -> GenreHelper.genreOf(t), genre);
    }

    public double artistAffinity(String artist) {
        return affinity(t -> norm(t.artists), norm(artist));
    }

    public double languageAffinity(String language) {
        return affinity(t -> lang(t), lang(language));
    }

    public double moodAffinity(String mood) {
        return affinity(t -> ListeningContext.detectMood(
                t.name + " " + t.artists + " " + t.album), mood);
    }

    /** Successful recent tracks (completion/like/replay), newest last. */
    public synchronized List<Track> recentSuccesses(int n) {
        List<Track> out = new ArrayList<>();
        for (Entry e : recent) {
            if (e.signal.isSuccess()) out.add(e.track);
        }
        while (out.size() > n) out.remove(0);
        return out;
    }

    /** Artists skipped early this session (strong negatives). */
    public synchronized Set<String> recentSkips() {
        Set<String> out = new HashSet<>();
        for (Entry e : recent) {
            if (e.signal == Signal.EARLY_SKIP || e.signal == Signal.SKIP) {
                String a = norm(e.track.artists);
                if (!a.isEmpty()) out.add(a);
            }
        }
        return out;
    }

    /** Weighted genre histogram of the session (for blending/diagnostics). */
    public synchronized Map<String, Double> genreHistogram() {
        Map<String, Double> out = new HashMap<>();
        int i = 0;
        for (Entry e : recent) {
            double recency = 0.5 + 0.5 * (i + 1.0) / Math.max(1, recent.size());
            String g = GenreHelper.genreOf(e.track);
            out.put(g, out.getOrDefault(g, 0.0) + recency * Math.signum(e.signal.weight == 0 ? 1 : e.signal.weight));
            i++;
        }
        return out;
    }

    private static String norm(String s) {
        return TitleGuard.norm(s == null ? "" : s);
    }

    private static String lang(Track t) {
        return lang(t.language);
    }

    private static String lang(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

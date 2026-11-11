package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Same-title guard: one song title must never play back-to-back with itself,
 * no matter how many versions or covers of it sit in the queue.
 *
 * Why this exists: a search for one song returns a wall of same-title
 * recordings (originals, covers, live, slowed...). Played raw, NEXT walks
 * through the same name 4-5 times in a row. The backend radio path already
 * guarantees unique titles per batch; this enforces the same invariant on
 * the Android side for played queues in every shuffle mode.
 *
 * The canonical key is TITLE-ONLY (artist is deliberately ignored): two
 * different artists' recordings of one title feel like the same song
 * repeating. Version keywords, brackets, feat. credits and punctuation are
 * all stripped so "Perfect", "Perfect (Acoustic)" and "Perfect - Slowed"
 * share one key.
 */
public final class TitleGuard {

    private TitleGuard() {
    }

    /** General normalization (lowercase, no brackets/punctuation) — public
     * cross-package equivalent of the smartshuffle-internal norm. */
    public static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("[-–—:;._+/,&]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Canonical song title: lowercase, no versions/brackets/feat/punctuation. */
    public static String canonicalTitle(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase();
        // Bracketed content first: (Acoustic), [Official Video], (Taylor's Version)...
        s = s.replaceAll("\\([^)]*\\)", " ");
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("\\{[^}]*\\}", " ");
        // Version / variant keywords.
        s = s.replaceAll("\\b(acoustic|unplugged|live|remix|remixed|remaster|remastered|reimagined|cover|instrumental|karaoke|slowed|reverb|reverbed|sped\\s*up|nightcore|lofi|lo-fi|8d|bass\\s*boosted|edit|version|original|radio\\s*edit|extended|club\\s*mix|demo|lyrics|lyric|video|official|music|audio|visualizer|taylor'?s\\s*version)\\b", " ");
        // Feat. credits: "Song feat. X" / "Song ft X".
        s = s.replaceAll("\\b(feat\\.?|ft\\.?|featuring)\\b.*$", " ");
        // "Title - From Movie" / "Title — Album" suffixes.
        s = s.replaceAll("\\s[-–—:;._+/,|]\\s.*$", " ");
        s = s.replaceAll("['\"`!?.]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static String canonicalTitle(Track t) {
        return t == null ? "" : canonicalTitle(t.name);
    }

    /** True when both tracks feel like the same song by title. */
    public static boolean isSameTitle(Track a, Track b) {
        if (a == null || b == null) return false;
        String ka = canonicalTitle(a);
        if (ka.isEmpty()) return false;
        return ka.equals(canonicalTitle(b));
    }

    /**
     * Stable decluster: keeps the track at {@code startIndex} first, then
     * orders the rest so no two adjacent tracks share a canonical title
     * whenever ANY such arrangement exists (classic reorganize-string:
     * always emit the largest remaining title group that differs from the
     * last emitted one). Same-title leftovers that cannot be separated are
     * appended at the end (still playable, never adjacent when avoidable).
     * Never drops tracks; returns a new list.
     */
    public static List<Track> decluster(List<Track> tracks, int startIndex) {
        if (tracks == null || tracks.size() < 3) {
            return tracks == null ? new ArrayList<Track>() : new ArrayList<>(tracks);
        }
        int start = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        List<Track> remaining = new ArrayList<>(tracks);
        Track first = remaining.remove(start);

        // Group by canonical title, preserving original order. Blank titles
        // are each unique so they always act as separators, never as a run.
        java.util.LinkedHashMap<String, java.util.ArrayDeque<Track>> groups =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> firstSeen = new java.util.HashMap<>();
        for (int i = 0; i < remaining.size(); i++) {
            Track t = remaining.get(i);
            String key = canonicalTitle(t);
            if (key.isEmpty()) key = "\0blank\0" + i;
            java.util.ArrayDeque<Track> q = groups.get(key);
            if (q == null) {
                q = new java.util.ArrayDeque<>();
                groups.put(key, q);
                firstSeen.put(key, i);
            }
            q.add(t);
        }

        List<Track> out = new ArrayList<>(tracks.size());
        out.add(first);
        String lastKey = canonicalTitle(first);
        if (lastKey.isEmpty()) lastKey = "\0first\0";

        int left = remaining.size();
        while (left > 0) {
            // Largest group that differs from the last emitted title;
            // ties break toward earliest original order (stable).
            String best = null;
            int bestSize = 0;
            int bestSeen = Integer.MAX_VALUE;
            for (java.util.Map.Entry<String, java.util.ArrayDeque<Track>> e : groups.entrySet()) {
                java.util.ArrayDeque<Track> q = e.getValue();
                if (q.isEmpty() || e.getKey().equals(lastKey)) continue;
                int seen = firstSeen.get(e.getKey());
                if (q.size() > bestSize || (q.size() == bestSize && seen < bestSeen)) {
                    best = e.getKey();
                    bestSize = q.size();
                    bestSeen = seen;
                }
            }
            if (best == null) {
                // Everything left shares the last title (pigeonhole):
                // append in original order — separation is impossible.
                for (java.util.ArrayDeque<Track> q : groups.values()) {
                    out.addAll(q);
                }
                break;
            }
            Track next = groups.get(best).poll();
            out.add(next);
            left--;
            lastKey = best;
        }
        return out;
    }

    /**
     * Counts adjacent same-title pairs in order. Zero means the invariant
     * holds (or the list is too short to violate it).
     */
    public static int countAdjacentSameTitle(List<Track> tracks) {
        if (tracks == null || tracks.size() < 2) return 0;
        int n = 0;
        for (int i = 1; i < tracks.size(); i++) {
            if (isSameTitle(tracks.get(i - 1), tracks.get(i))) n++;
        }
        return n;
    }

    /** True when the list holds at least two distinct non-empty titles. */
    public static boolean hasTitleVariety(List<Track> tracks) {
        if (tracks == null) return false;
        Set<String> keys = new HashSet<>();
        for (Track t : tracks) {
            String k = canonicalTitle(t);
            if (!k.isEmpty() && keys.add(k) && keys.size() >= 2) return true;
        }
        return false;
    }
}

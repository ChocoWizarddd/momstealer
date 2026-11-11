package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Smart shuffle: unbiased Fisher-Yates permutations, scored and picked.
 *
 * <p>Every candidate order starts as a Fisher-Yates shuffle (see
 * {@link ShuffleUtils}) so the output stays a uniform random permutation at
 * its core. {@link #NUM_CANDIDATES} candidates are drawn, adjacent same-artist
 * runs are broken up, the pinned track (if any) is moved first, and the
 * highest-scoring candidate wins. Recently played tracks are penalized —
 * never boosted — so just-played songs drift away from the front.
 */
public class SmartShuffler {

    private static final int NUM_CANDIDATES = 8;
    private static final double RECENCY_WEIGHT = 6.0;
    private static final double EARLY_WINDOW_WEIGHT = 26.0;
    private static final double ADJACENT_ARTIST_WEIGHT = 40.0;
    private static final double WINDOW_ARTIST_WEIGHT = 9.0;
    private static final double PATTERN_WEIGHT = 7.0;
    private static final double ALTERNATING_WEIGHT = 11.0;
    private static final double FRESHNESS_WEIGHT = 3.0;
    private static final double OPENER_FRESH_WEIGHT = 8.0;

    private final List<String> recentIds;
    private final Random rng;

    /** @param recent recently played tracks, most-recent first (may be empty) */
    public SmartShuffler(List<Track> recent, Random rng) {
        List<String> ids = new ArrayList<>();
        if (recent != null) {
            for (Track t : recent) {
                if (t != null && t.id != null) ids.add(t.id);
            }
        }
        this.recentIds = ids;
        this.rng = rng != null ? rng : new Random();
    }

    public List<Track> shuffle(List<Track> songs) {
        return shuffle(songs, (Track) null);
    }

    /** Shuffles with {@code pinned} forced first (permutation otherwise kept). */
    public List<Track> shuffle(List<Track> songs, Track pinned) {
        return SmartShuffler.shuffle(songs, recentIds, pinned != null ? pinned.id : null, rng);
    }

    /**
     * Whole-order score: higher is better. Recent tracks up front, adjacent /
     * windowed same-artist runs, A-X-A patterns and A-B-A-B alternations lower
     * it; fresh tracks (and a fresh opener) raise it.
     */
    public double score(List<Track> order) {
        if (order == null || order.isEmpty()) return 0;
        Set<String> recentSet = new HashSet<>(recentIds);
        Map<String, Integer> recentPosition = new HashMap<>();
        for (int i = 0; i < recentIds.size(); i++) {
            if (!recentPosition.containsKey(recentIds.get(i))) {
                recentPosition.put(recentIds.get(i), i);
            }
        }
        return scoreOrder(order, recentSet, recentPosition);
    }

    // ---- Static API (same behavior; recent = id list, most-recent first) ----

    public static List<Track> shuffle(List<Track> songs, List<String> recentIds, String currentId) {
        return SmartShuffler.shuffle(songs, recentIds, currentId, new Random());
    }

    public static List<Track> shuffle(List<Track> songs, List<String> recentIds, String currentId, Random rng) {
        if (songs == null) return new ArrayList<>();
        if (songs.size() <= 1) return new ArrayList<>(songs);
        Random random = rng != null ? rng : new Random();
        List<String> recents = recentIds != null ? recentIds : Collections.<String>emptyList();
        if (songs.size() <= 3) {
            List<Track> copy = new ArrayList<>(songs);
            ShuffleUtils.fisherYatesShuffle(copy, random);
            if (currentId != null) moveFirst(copy, currentId);
            return copy;
        }

        Set<String> recentSet = new HashSet<>(recents);
        Map<String, Integer> recentPosition = new HashMap<>();
        for (int i = 0; i < recents.size(); i++) {
            if (!recentPosition.containsKey(recents.get(i))) {
                recentPosition.put(recents.get(i), i);
            }
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        List<Track> bestOrder = null;

        for (int c = 0; c < NUM_CANDIDATES; c++) {
            List<Track> candidate = new ArrayList<>(songs);
            ShuffleUtils.fisherYatesShuffle(candidate, random);

            candidate = SpotifyShuffler.breakUpAdjacentArtists(candidate, random);

            if (currentId != null) moveFirst(candidate, currentId);

            double score = scoreOrder(candidate, recentSet, recentPosition);
            if (score > bestScore) {
                bestScore = score;
                bestOrder = candidate;
            }
        }

        if (bestOrder != null && currentId == null && !recents.isEmpty()) {
            if (bestOrder.get(0).id.equals(recents.get(0))) {
                int swapIdx = random.nextInt(Math.max(1, bestOrder.size() / 2)) + 1;
                if (swapIdx < bestOrder.size()) {
                    Track temp = bestOrder.get(0);
                    bestOrder.set(0, bestOrder.get(swapIdx));
                    bestOrder.set(swapIdx, temp);
                }
            }
        }

        return bestOrder != null ? bestOrder : new ArrayList<>(songs);
    }

    private static void moveFirst(List<Track> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) {
                if (i > 0) list.add(0, list.remove(i));
                return;
            }
        }
    }

    private static double scoreOrder(List<Track> candidate,
                                     Set<String> recentSet,
                                     Map<String, Integer> recentPosition) {
        int n = candidate.size();
        double score = 0;
        int earlyWindow = Math.max(2, n / 8);
        int recentCount = Math.max(recentPosition.size(), 1);

        for (int i = 0; i < n; i++) {
            Track song = candidate.get(i);

            // Recently played: most-recent (rank 0) penalized most, decaying
            // with rank and with depth into the queue. Never a bonus.
            Integer rank = recentPosition.get(song.id);
            if (rank != null) {
                score -= RECENCY_WEIGHT * (recentCount - rank) * (1.0 - (double) i / n);
            }

            // A recent track sitting in the opening window is extra bad.
            if (recentSet.contains(song.id) && i < earlyWindow) {
                score -= EARLY_WINDOW_WEIGHT * (earlyWindow - i);
            }

            String artistLower = song.artists != null ? song.artists.toLowerCase() : "";
            if (i < n - 1) {
                Track nextSong = candidate.get(i + 1);
                String nextArtistLower = nextSong.artists != null ? nextSong.artists.toLowerCase() : "";
                if (artistLower.equals(nextArtistLower)) {
                    score -= ADJACENT_ARTIST_WEIGHT;
                }
            }

            for (int w = 2; w <= 3 && i + w < n; w++) {
                Track wSong = candidate.get(i + w);
                String wArtistLower = wSong.artists != null ? wSong.artists.toLowerCase() : "";
                if (artistLower.equals(wArtistLower)) {
                    score -= WINDOW_ARTIST_WEIGHT;
                }
            }

            if (i + 2 < n) {
                Track i2Song = candidate.get(i + 2);
                String i2ArtistLower = i2Song.artists != null ? i2Song.artists.toLowerCase() : "";
                if (artistLower.equals(i2ArtistLower)) {
                    score -= PATTERN_WEIGHT;
                }
            }

            if (i + 3 < n) {
                String a = artistLower;
                Track bSong = candidate.get(i + 1);
                String b = bSong.artists != null ? bSong.artists.toLowerCase() : "";
                Track cSong = candidate.get(i + 2);
                String c = cSong.artists != null ? cSong.artists.toLowerCase() : "";
                Track dSong = candidate.get(i + 3);
                String d = dSong.artists != null ? dSong.artists.toLowerCase() : "";
                if (a.equals(c) && b.equals(d) && !a.equals(b)) {
                    score -= ALTERNATING_WEIGHT;
                }
            }

            if (!recentSet.contains(song.id)) {
                score += FRESHNESS_WEIGHT * (1.0 + (double) (n - i) / n);
            }

            if (i == 0 && !recentSet.contains(song.id)) {
                score += OPENER_FRESH_WEIGHT;
            }
        }

        return score;
    }
}

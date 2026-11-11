package com.nightlight.app.shuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The canonical Smart order is a SOFT-penalty, multi-candidate
 * selector: it generates several complete seeded permutations,
 * scores them on the diversity terms, and returns the best.
 * Track uniqueness is the only hard rule — a pool that cannot
 * be spread is relaxed automatically, never rejected.
 */
public class SoftPenaltyShuffleTest {

    private static Track track(String id, String title, String artist, String genre) {
        return new Track(id, title, artist, "Album " + id, null, "http://s/" + id,
                1000L, "2024", 0L, "english", genre);
    }

    private static Map<String, Track> byId(List<Track> pool) {
        Map<String, Track> map = new HashMap<>();
        for (Track t : pool) map.put(t.id, t);
        return map;
    }

    private static List<Track> specPool() {
        List<Track> pool = new ArrayList<>();
        pool.add(track("A1", "Alpha", "Artist A", "rock"));
        pool.add(track("A2", "Bravo", "Artist A", "rock"));
        pool.add(track("A3", "Charlie", "Artist A", "rock"));
        pool.add(track("B1", "Delta", "Artist B", "pop"));
        pool.add(track("B2", "Echo", "Artist B", "pop"));
        pool.add(track("C1", "Foxtrot", "Artist C", "edm"));
        pool.add(track("C2", "Golf", "Artist C", "edm"));
        pool.add(track("D1", "Hotel", "Artist D", "indie"));
        pool.add(track("D2", "India", "Artist D", "indie"));
        pool.add(track("E1", "Juliett", "Artist E", "hiphop"));
        pool.add(track("E2", "Kilo", "Artist E", "hiphop"));
        pool.add(track("F1", "Lima", "Artist F", "jazz"));
        return pool;
    }

    /** 12 tracks, 5 from one artist — the impossible-spacing example. */
    private static List<Track> dominantArtistPool() {
        List<Track> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) pool.add(track("a" + i, "Song A" + i, "Dominant", "pop"));
        for (int i = 0; i < 7; i++) pool.add(track("b" + i, "Song B" + i, "Artist " + i, "rock"));
        return pool;
    }

    @Test
    public void dominantArtistPoolIsNeverRejected() {
        List<Track> pool = dominantArtistPool();
        Map<String, Track> map = byId(pool);
        int adjacentDominantPairs = 0;
        int dominantRunsTotal = 0;
        for (long seed = 0; seed < 25; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, null);
            assertEquals("complete permutation, no rejection", 12, order.size());
            assertEquals("every track exactly once", 12, new HashSet<>(order).size());

            int runs = 0;
            boolean previousWasDominant = false;
            for (String id : order) {
                boolean isDominant = "Dominant".equals(map.get(id).artists);
                if (isDominant) {
                    if (!previousWasDominant) runs++;
                    else adjacentDominantPairs++;
                }
                previousWasDominant = isDominant;
            }
            dominantRunsTotal += runs;
        }
        assertTrue("dominant artist should be spread into runs, got "
                + dominantRunsTotal + " runs over 25 seeds", dominantRunsTotal >= 75);
        assertTrue("dominant artist back-to-back pairs should be rare, got "
                + adjacentDominantPairs, adjacentDominantPairs <= 2);
    }

    @Test
    public void smartOrderIsDeterministicForTheSameSeed() {
        List<Track> pool = specPool();
        List<String> history = Arrays.asList("A1", "B1");
        List<String> first = ShuffleOrderFactory.smartOrder(pool, 42L, history);
        for (int i = 0; i < 3; i++) {
            assertEquals("same seed + history must reproduce the same order",
                    first, ShuffleOrderFactory.smartOrder(pool, 42L, history));
        }
        assertNotEquals("a different seed should usually differ",
                first, ShuffleOrderFactory.smartOrder(pool, 43L, history));
    }

    @Test
    public void smartOrderOutscoresPlainShuffle() {
        List<List<Track>> pools = Arrays.asList(specPool(), dominantArtistPool());
        for (List<Track> pool : pools) {
            int wins = 0;
            int runs = 20;
            for (long seed = 0; seed < runs; seed++) {
                List<String> smart = ShuffleOrderFactory.smartOrder(pool, seed, null);
                List<String> plain = ShuffleOrderFactory.plainOrder(pool, seed);
                double smartScore = ShuffleOrderFactory.scoreOrder(smart, pool, null);
                double plainScore = ShuffleOrderFactory.scoreOrder(plain, pool, null);
                if (smartScore >= plainScore) wins++;
            }
            assertTrue("smart order should beat plain Fisher-Yates on diversity, got "
                    + wins + "/" + runs, wins >= runs * 0.6);
        }
    }

    @Test
    public void scoreRanksSpacedOrderAboveClusteredOrder() {
        // Same genre-free shape: distinct artists, albums and titles,
        // one shared genre so only spacing differs.
        List<Track> pool = new ArrayList<>();
        pool.add(track("a1", "T1", "Artist A", "rock"));
        pool.add(track("a2", "T2", "Artist A", "rock"));
        pool.add(track("b1", "T3", "Artist B", "pop"));
        pool.add(track("b2", "T4", "Artist B", "pop"));
        pool.add(track("c1", "T5", "Artist C", "edm"));
        pool.add(track("c2", "T6", "Artist C", "edm"));

        List<String> clustered = Arrays.asList("a1", "a2", "b1", "b2", "c1", "c2");
        List<String> spaced = Arrays.asList("a1", "b1", "c1", "a2", "b2", "c2");

        assertTrue("spaced order must score above the clustered one",
                ShuffleOrderFactory.scoreOrder(spaced, pool, null)
                        > ShuffleOrderFactory.scoreOrder(clustered, pool, null));
    }

    @Test
    public void historyTracksArePushedLaterNotDropped() {
        List<Track> pool = specPool();
        List<String> history = Arrays.asList("A1", "B1"); // oldest first
        double historySum = 0, freshSum = 0;
        for (long seed = 0; seed < 25; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, history);
            assertTrue("history tracks stay eligible", order.containsAll(history));
            for (int i = 0; i < order.size(); i++) {
                if (history.contains(order.get(i))) historySum += i;
                else freshSum += i;
            }
        }
        assertTrue("recently heard tracks should drift later on average",
                historySum / 2.0 > freshSum / 10.0);
    }

    @Test
    public void emptyPoolReturnsEmptyAndSingleTrackPoolReturnsTheTrack() {
        assertEquals(0, ShuffleOrderFactory.smartOrder(new ArrayList<Track>(), 1L, null).size());
        List<Track> single = new ArrayList<>();
        single.add(track("solo", "Only Song", "Only Artist", "pop"));
        List<String> order = ShuffleOrderFactory.smartOrder(single, 1L, null);
        assertEquals(Arrays.asList("solo"), order);
    }

    @Test
    public void wallOfOneSongStillProducesACompleteOrder() {
        List<Track> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pool.add(track("v" + i, "Same Song", "Artist " + i, "pop"));
        }
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 9L, null);
        assertEquals(5, order.size());
        assertEquals(5, new HashSet<>(order).size());
    }

    @Test
    public void candidateCountScalesDownForHugePools() {
        List<Track> small = specPool();
        ShuffleOrderFactory.smartOrder(small, 1L, null);
        assertEquals(8, ShuffleOrderFactory.lastCandidateCount());
        assertEquals(small.size(), ShuffleOrderFactory.lastPoolSize());

        List<Track> huge = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            huge.add(track("h" + i, "Song " + i, "Artist " + (i % 40), "genre" + (i % 12)));
        }
        ShuffleOrderFactory.smartOrder(huge, 1L, null);
        assertEquals("huge pools must degrade to few candidates",
                2, ShuffleOrderFactory.lastCandidateCount());
        List<String> order = ShuffleOrderFactory.smartOrder(huge, 2L, null);
        assertEquals(3000, order.size());
        assertEquals(3000, new HashSet<>(order).size());
    }
}

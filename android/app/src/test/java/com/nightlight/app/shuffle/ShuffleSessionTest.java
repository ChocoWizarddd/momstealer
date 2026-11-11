package com.nightlight.app.shuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executable form of the shuffle contract: shuffle the playlist first, then play
 * the shuffled order. Nothing regenerates the order except an explicit reshuffle,
 * a new source, or a genuinely exhausted cycle.
 */
public class ShuffleSessionTest {

    private static Track track(String id, String title, String artist, String genre) {
        return new Track(id, title, artist, "Album " + id, null, "http://s/" + id, 1000L, "2024",
                0L, "english", genre);
    }

    /** A1..A3, B1..B2, C1..C2, D1..D2, E1..E2, F1 - the spec's 12-song example. */
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

    private static Map<String, Track> byId(List<Track> pool) {
        Map<String, Track> map = new HashMap<>();
        for (Track t : pool) map.put(t.id, t);
        return map;
    }

    private static int adjacentSameArtistCount(List<String> order, Map<String, Track> map) {
        int n = 0;
        for (int i = 1; i < order.size(); i++) {
            Track a = map.get(order.get(i - 1));
            Track b = map.get(order.get(i));
            if (a != null && b != null
                    && TitleGuardNorm(a.artists).equals(TitleGuardNorm(b.artists))) n++;
        }
        return n;
    }

    private static int adjacentSameTitleCount(List<String> order, Map<String, Track> map) {
        int n = 0;
        for (int i = 1; i < order.size(); i++) {
            Track a = map.get(order.get(i - 1));
            Track b = map.get(order.get(i));
            if (a != null && b != null
                    && TitleGuardNorm(a.name).equals(TitleGuardNorm(b.name))) n++;
        }
        return n;
    }

    private static String TitleGuardNorm(String raw) {
        return com.nightlight.app.smartshuffle.TitleGuard.norm(raw);
    }

    private static String titleKey(Track t) {
        return com.nightlight.app.smartshuffle.TitleGuard.canonicalTitle(t.name);
    }

    /**
     * Adjacent repetition is only legitimate when nothing else was left to
     * play, i.e. when the whole remaining tail belongs to that one artist or
     * that one song. Anywhere else the order had an alternative and had to
     * take it.
     */
    private static void assertOnlyForcedRepeats(List<String> order, Map<String, Track> map,
                                                boolean byArtist) {
        for (int i = 1; i < order.size(); i++) {
            Track prev = map.get(order.get(i - 1));
            Track cur = map.get(order.get(i));
            String prevKey = byArtist ? TitleGuardNorm(prev.artists) : titleKey(prev);
            String curKey = byArtist ? TitleGuardNorm(cur.artists) : titleKey(cur);
            if (prevKey.isEmpty() || !prevKey.equals(curKey)) continue;
            for (int j = i; j < order.size(); j++) {
                Track t = map.get(order.get(j));
                String key = byArtist ? TitleGuardNorm(t.artists) : titleKey(t);
                assertEquals("repeat at " + i + " was avoidable (by "
                        + (byArtist ? "artist" : "title") + ")", prevKey, key);
            }
        }
    }

    // ---------------------------------------------------------------- engine

    @Test
    public void plainOrder_isACompletePermutation() {
        List<Track> pool = specPool();
        for (long seed = 0; seed < 50; seed++) {
            List<String> order = ShuffleOrderFactory.plainOrder(pool, seed);
            assertEquals(pool.size(), order.size());
            assertEquals("every track present exactly once",
                    new HashSet<>(ShuffleOrderFactory.ids(pool)), new HashSet<>(order));
        }
    }

    @Test
    public void smartOrder_isACompletePermutation() {
        List<Track> pool = specPool();
        for (long seed = 0; seed < 50; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, null);
            assertEquals(pool.size(), order.size());
            assertEquals(new HashSet<>(ShuffleOrderFactory.ids(pool)), new HashSet<>(order));
        }
    }

    @Test
    public void smartOrder_neverRepeatsAnArtistBackToBack() {
        List<Track> pool = specPool();
        Map<String, Track> map = byId(pool);
        for (long seed = 0; seed < 200; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, null);
            assertOnlyForcedRepeats(order, map, true);
        }
    }

    @Test
    public void smartOrder_neverRepeatsTheSameSongBackToBack() {
        List<Track> pool = new ArrayList<>();
        pool.add(track("S1", "Shape of You", "Artist X", "pop"));
        pool.add(track("S2", "Shape of You", "Artist Y", "pop"));
        pool.add(track("S3", "Shape of You (Acoustic)", "Artist Z", "pop"));
        pool.add(track("S4", "Shape of You - Live", "Artist W", "pop"));
        pool.add(track("S5", "Believer", "Artist X", "pop"));
        pool.add(track("S6", "Fire", "Artist Y", "pop"));
        pool.add(track("S7", "Thunder", "Artist Z", "pop"));
        Map<String, Track> map = byId(pool);
        for (long seed = 0; seed < 200; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, null);
            assertOnlyForcedRepeats(order, map, false);
        }
    }

    @Test
    public void smartOrder_keepsGenreProportionsWhileBreakingUpRuns() {
        List<Track> pool = new ArrayList<>();
        Map<String, String> genreById = new HashMap<>();
        Map<String, Integer> spec = new LinkedHashMap<>();
        spec.put("hiphop", 20);
        spec.put("pop", 10);
        spec.put("rnb", 6);
        spec.put("edm", 4);
        int n = 0;
        for (Map.Entry<String, Integer> e : spec.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                String id = "t" + (n++);
                pool.add(track(id, "Title " + id, "Artist " + id, e.getKey()));
                genreById.put(id, e.getKey());
            }
        }

        Map<String, Integer> totals = new HashMap<>();
        for (long seed = 0; seed < 60; seed++) {
            for (String id : ShuffleOrderFactory.smartOrder(pool, seed, null)) {
                totals.merge(genreById.get(id), 1, Integer::sum);
            }
        }
        int observedTotal = 0;
        for (int v : totals.values()) observedTotal += v;

        for (Map.Entry<String, Integer> e : spec.entrySet()) {
            double expected = e.getValue() / (double) pool.size();
            double actual = totals.getOrDefault(e.getKey(), 0) / (double) observedTotal;
            assertTrue(e.getKey() + " share " + actual + " drifted from " + expected,
                    Math.abs(actual - expected) < 0.10);
        }
    }

    @Test
    public void smartOrder_breaksUpLongGenreRuns() {
        List<Track> pool = new ArrayList<>();
        Map<String, String> genreById = new HashMap<>();
        int n = 0;
        for (int i = 0; i < 30; i++) {
            String id = "h" + (n++);
            pool.add(track(id, "Song " + id, "Artist " + id, "hiphop"));
            genreById.put(id, "hiphop");
        }
        for (int i = 0; i < 10; i++) {
            String id = "p" + (n++);
            pool.add(track(id, "Tune " + id, "Artist " + id, "pop"));
            genreById.put(id, "pop");
        }

        int clustered = 0;
        int runs = 40;
        long runTotal = 0;
        for (long seed = 0; seed < runs; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, null);
            int run = 1, maxRun = 1;
            for (int i = 1; i < order.size(); i++) {
                if (genreById.get(order.get(i)).equals(genreById.get(order.get(i - 1)))) run++;
                else run = 1;
                maxRun = Math.max(maxRun, run);
            }
            runTotal += maxRun;
            if (maxRun >= 10) clustered++;
        }
        double avgMaxRun = runTotal / (double) runs;
        assertTrue("mean longest genre run " + avgMaxRun + " is too clumpy", avgMaxRun < 8.0);
        assertTrue("long runs should be rare, saw " + clustered + "/" + runs, clustered <= 1);
    }

    @Test
    public void smartOrder_penalisesRecentlyPlayedWithoutForbiddingThem() {
        List<Track> pool = new ArrayList<>();
        for (char c = 'A'; c <= 'H'; c++) {
            pool.add(track(String.valueOf(c), "Title " + c, "Artist " + c, "pop"));
        }
        List<String> recent = Arrays.asList("A", "B", "C");
        double recentSum = 0, freshSum = 0;
        int runs = 40;
        for (long seed = 0; seed < runs; seed++) {
            List<String> order = ShuffleOrderFactory.smartOrder(pool, seed, recent);
            assertTrue("recent tracks stay eligible", order.containsAll(recent));
            for (int i = 0; i < order.size(); i++) {
                if (recent.contains(order.get(i))) recentSum += i;
                else freshSum += i;
            }
        }
        assertTrue("recently played should drift later on average",
                (recentSum / 3.0) > (freshSum / 5.0));
    }

    @Test
    public void smartOrder_spreadsADominantArtistWithoutFakeAlternation() {
        List<Track> pool = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < 80; i++) pool.add(track("m" + (n++), "Song " + n, "Artist A", "pop"));
        for (int i = 0; i < 5; i++) pool.add(track("b" + i, "Song B" + i, "Artist B", "pop"));
        for (int i = 0; i < 5; i++) pool.add(track("c" + i, "Song C" + i, "Artist C", "pop"));
        for (int i = 0; i < 5; i++) pool.add(track("d" + i, "Song D" + i, "Artist D", "pop"));
        for (int i = 0; i < 5; i++) pool.add(track("e" + i, "Song E" + i, "Artist E", "pop"));

        List<String> order = ShuffleOrderFactory.smartOrder(pool, 42L, null);
        assertEquals(100, order.size());
        assertEquals(100, new HashSet<>(order).size());

        int maxRun = 1, run = 1;
        for (int i = 1; i < order.size(); i++) {
            if (order.get(i).startsWith("m") && order.get(i - 1).startsWith("m")) run++;
            else run = 1;
            maxRun = Math.max(maxRun, run);
        }
        assertOnlyForcedRepeats(order, byId(pool), true);

        int aRuns = 0;
        boolean previousWasA = false;
        for (String id : order) {
            boolean isA = id.startsWith("m");
            if (isA && !previousWasA) aRuns++;
            previousWasA = isA;
        }
        assertTrue("Artist A should be broken up by the other artists, got " + aRuns
                + " separate runs", aRuns >= 12);

        int others = 0;
        for (String id : order) if (!id.startsWith("m")) others++;
        assertEquals(20, others);
    }

    @Test
    public void smartOrder_singleArtistPoolStillTerminatesAndCoversEverything() {
        List<Track> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) pool.add(track("s" + i, "Song " + i, "Only Artist", "pop"));
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 7L, null);
        assertEquals(6, order.size());
        assertEquals(6, new HashSet<>(order).size());
    }

    @Test
    public void smartOrder_wallOfOneSongStillTerminates() {
        List<Track> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) pool.add(track("v" + i, "Same Song", "Artist " + i, "pop"));
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 9L, null);
        assertEquals(5, order.size());
        assertEquals(5, new HashSet<>(order).size());
    }

    // --------------------------------------------------------------- session

    @Test
    public void advanceMovesTheCursorAndNeverTouchesTheOrder() {
        List<Track> pool = specPool();
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 1234L, null);
        ShuffleSession session = new ShuffleSession("s1", 1234L, "src", true, 0, 0L, order);
        List<String> snapshot = new ArrayList<>(session.order());

        for (int i = 0; i < snapshot.size(); i++) {
            String id = session.advance();
            assertNotNull("session must not run dry mid-cycle", id);
            assertEquals("advance returns the order entry at the cursor", snapshot.get(i), id);
            assertEquals("order is immutable during playback", snapshot, session.order());
        }
        assertNull("a spent cycle returns nothing", session.advance());
        assertTrue(session.isComplete());
    }

    @Test
    public void fullCyclePlaysEveryUniqueTrackExactlyOnce() {
        List<Track> pool = specPool();
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 555L, null);
        ShuffleSession session = new ShuffleSession("s", 555L, "src", true, 0, 0L, order);

        Set<String> played = new LinkedHashSet<>();
        while (session.hasNext()) played.add(session.advance());

        assertEquals(pool.size(), played.size());
        assertEquals(new HashSet<>(ShuffleOrderFactory.ids(pool)), played);
        assertTrue(session.isComplete());
    }

    @Test
    public void rewindMovesTheCursorBackAndUnplays() {
        List<Track> pool = specPool();
        ShuffleSession session = new ShuffleSession("s", 1L, "src", true, 0, 0L,
                ShuffleOrderFactory.smartOrder(pool, 1L, null));
        session.advance();
        session.advance();
        session.advance();
        assertEquals(3, session.cursor());
        String back = session.rewind();
        assertEquals(2, session.cursor());
        assertEquals(session.order().get(2), back);
        assertFalse(session.hasPlayed(back));
        assertEquals(back, session.advance());
    }

    @Test
    public void remainingIsTheUnplayedTailInOrder() {
        List<Track> pool = specPool();
        ShuffleSession session = new ShuffleSession("s", 2L, "src", true, 0, 0L,
                ShuffleOrderFactory.plainOrder(pool, 2L));
        session.advance();
        session.advance();
        List<String> remaining = session.remaining();
        assertEquals(session.order().size() - 2, remaining.size());
        assertEquals(session.order().subList(2, session.order().size()), remaining);
        assertEquals(3, session.remaining(3).size());
    }

    // ---------------------------------------------------------------- refill

    @Test
    public void refillAppendsTheNextSliceOfTheSameOrder() {
        List<Track> pool = specPool();
        Map<String, Track> map = byId(pool);
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 77L, null);
        ShuffleSession session = new ShuffleSession("s", 77L, "src", true, 0, 0L, order);

        String playing = session.advance();
        List<String> queued = new ArrayList<>();
        queued.add(playing);
        queued.addAll(ShuffleQueuePlanner.refill(session, queued, session.playedIds(), 5));

        assertEquals(6, queued.size());
        assertEquals("queue is a prefix of the session order",
                session.order().subList(0, 6), queued);
        assertEquals("refill appended from the same order", 0,
                adjacentSameArtistCount(queued, map));
    }

    @Test
    public void refillIsIdempotent() {
        List<Track> pool = specPool();
        ShuffleSession session = new ShuffleSession("s", 3L, "src", true, 0, 0L,
                ShuffleOrderFactory.smartOrder(pool, 3L, null));
        session.advance();
        List<String> queued = new ArrayList<>();
        queued.addAll(ShuffleQueuePlanner.refill(session, queued, session.playedIds(), 6));

        List<String> second = ShuffleQueuePlanner.refill(session, queued, session.playedIds(), 6);
        assertTrue("a second refill with an unchanged queue must add nothing", second.isEmpty());
    }

    @Test
    public void refillNeverReintroducesAPlayedTrack() {
        List<Track> pool = specPool();
        ShuffleSession session = new ShuffleSession("s", 4L, "src", true, 0, 0L,
                ShuffleOrderFactory.smartOrder(pool, 4L, null));
        List<String> played = new ArrayList<>();
        List<String> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = session.advance();
            played.add(id);
            queued.remove(id);
            queued.addAll(ShuffleQueuePlanner.refill(session, queued, session.playedIds(), 4));
            for (String q : queued) {
                assertFalse("played track re-entered the queue: " + q, played.contains(q));
            }
        }
        assertEquals(6, new HashSet<>(played).size());
    }

    @Test
    public void refillNeverRegeneratesTheOrder() {
        List<Track> pool = specPool();
        ShuffleSession session = new ShuffleSession("s", 5L, "src", true, 0, 0L,
                ShuffleOrderFactory.smartOrder(pool, 5L, null));
        List<String> before = new ArrayList<>(session.order());
        for (int i = 0; i < 8; i++) {
            session.advance();
            ShuffleQueuePlanner.refill(session, before, session.playedIds(), 3);
        }
        assertEquals(before, session.order());
    }

    // ------------------------------------------------------------ coordinator

    @Test
    public void coordinatorResumesTheSameSessionOnRepeatedCalls() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        ShuffleSession first = coordinator.ensureSession(pool, "playlist-1", true, null);
        String sessionId = first.sessionId;
        List<String> order = new ArrayList<>(first.order());

        for (int i = 0; i < 5; i++) {
            ShuffleSession again = coordinator.ensureSession(pool, "playlist-1", true, null);
            assertEquals(sessionId, again.sessionId);
            assertEquals(order, again.order());
            coordinator.advance();
        }
    }

    @Test
    public void coordinatorDoesNotRegenerateOnLowQueue() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        ShuffleSession session = coordinator.ensureSession(pool, "p", true, null);
        List<String> order = new ArrayList<>(session.order());
        String sessionId = session.sessionId;

        List<String> queued = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = coordinator.advance();
            assertNotNull(id);
            queued.remove(id);
            queued.addAll(coordinator.refillPlan(queued, 2));
            assertEquals("a low queue must not rebuild the order", order, coordinator.session().order());
            assertEquals(sessionId, coordinator.session().sessionId);
        }
    }

    @Test
    public void coordinatorNextIsACursorMoveNotARandomPick() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        ShuffleSession session = coordinator.ensureSession(pool, "p", true, null);
        List<String> expected = new ArrayList<>(session.order());

        for (int i = 0; i < expected.size(); i++) {
            assertEquals("press " + i, expected.get(i), coordinator.advance());
        }
        assertNull("exhausted session yields nothing until a new cycle starts",
                coordinator.advance());
        assertTrue(coordinator.isCycleComplete());
    }

    @Test
    public void coordinatorExplicitReshuffleCreatesABrandNewOrder() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        ShuffleSession first = coordinator.ensureSession(pool, "p", true, null);
        List<String> order = new ArrayList<>(first.order());
        coordinator.advance();

        ShuffleSession reshuffled = coordinator.startNewSession(pool, "p", true, null);
        assertNotEquals("explicit reshuffle must not replay the old order",
                order, reshuffled.order());
        assertEquals(0, reshuffled.cursor());
        assertTrue(reshuffled.cycle > first.cycle);
    }

    @Test
    public void coordinatorNewCycleIsADifferentOrder() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        List<String> firstOrder = new ArrayList<>(coordinator.startNewSession(pool, "p", true, null).order());
        while (!coordinator.isCycleComplete()) coordinator.advance();

        ShuffleSession second = coordinator.startNextCycle(pool, "p", true, null);
        assertNotEquals("a new cycle must be a new order, not a replay", firstOrder, second.order());
        assertEquals(new HashSet<>(ShuffleOrderFactory.ids(pool)), new HashSet<>(second.order()));
    }

    @Test
    public void coordinatorReplacesSessionWhenTheSourceChanges() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        ShuffleSession a = coordinator.ensureSession(specPool(), "playlist-1", true, null);
        List<String> orderA = new ArrayList<>(a.order());
        ShuffleSession b = coordinator.ensureSession(specPool(), "playlist-2", true, null);
        assertNotEquals(orderA, b.order());
        assertEquals("playlist-2", b.sourcePlaylistId);
    }

    @Test
    public void coordinatorModeChangeReplacesTheSession() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        ShuffleSession smart = coordinator.ensureSession(specPool(), "p", true, null);
        assertTrue(smart.smart);
        ShuffleSession plain = coordinator.ensureSession(specPool(), "p", false, null);
        assertFalse(plain.smart);
    }

    // ---------------------------------------------------------- manual queue

    @Test
    public void manualPlayNextNeverEntersTheSessionOrder() {
        ShuffleCoordinator coordinator = new ShuffleCoordinator();
        List<Track> pool = specPool();
        ShuffleSession session = coordinator.startNewSession(pool, "p", true, null);
        List<String> order = new ArrayList<>(session.order());
        int cursor = session.cursor();

        coordinator.manualQueue().playNext("X");

        assertFalse(order.contains("X"));
        assertEquals(order, session.order());
        assertEquals("the session cursor must not move for a manual insert",
                cursor, session.cursor());
        assertEquals("X", coordinator.manualQueue().peek());
    }

    @Test
    public void manualItemPlaysBeforeContextWithoutConsumingIt() {
        List<String> manual = new ArrayList<>(Arrays.asList("X", "Y"));
        ShuffleSession session = new ShuffleSession("s", 1L, "p", true, 0, 0L,
                ShuffleOrderFactory.plainOrder(specPool(), 1L));
        session.advance();
        String nextContext = session.peekNextId();

        List<String> spliced = ShuffleQueuePlanner.mergeManualNext(manual, nextContext);
        assertEquals(Arrays.asList("X", "Y", nextContext), spliced);
        assertFalse(session.hasPlayed(nextContext));
    }

    @Test
    public void manualQueuePreservesInsertionOrder() {
        ManualQueue q = new ManualQueue();
        q.playNext("X");
        q.playNext("Y");
        assertEquals(Arrays.asList("Y", "X"), q.snapshot());
        assertEquals("Y", q.poll());
        assertEquals("X", q.poll());
        assertTrue(q.isEmpty());
    }

    // ------------------------------------------------------------ persistence

    @Test
    public void sessionSurvivesSerialisationRoundTrip() {
        List<Track> pool = specPool();
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 31L, null);
        ShuffleSession session = new ShuffleSession("shuffle-1", 31L, "p", true, 2, 999L, order);
        session.advance();
        session.advance();
        session.advance();
        session.rewind();

        ShuffleSession restored = ShuffleSessionStore.fromJson(ShuffleSessionStore.toJson(session));
        assertNotNull(restored);
        assertEquals(order, restored.order());
        assertEquals(session.cursor(), restored.cursor());
        assertEquals(session.playedIds(), restored.playedIds());
        assertEquals("shuffle-1", restored.sessionId);
        assertEquals(2, restored.cycle);
    }

    @Test
    public void restoredSessionContinuesTheSameOrder() {
        List<Track> pool = specPool();
        List<String> order = ShuffleOrderFactory.smartOrder(pool, 41L, null);
        ShuffleSession session = new ShuffleSession("s", 41L, "p", true, 0, 0L, order);
        session.advance();
        session.advance();

        ShuffleSession restored = ShuffleSessionStore.fromJson(ShuffleSessionStore.toJson(session));
        assertEquals(order.get(2), restored.advance());
        assertEquals(order.get(3), restored.advance());
    }

    @Test
    public void corruptPayloadYieldsNoSession() {
        assertNull(ShuffleSessionStore.fromJson(null));
        assertNull(ShuffleSessionStore.fromJson(""));
        assertNull(ShuffleSessionStore.fromJson("{not json"));
        assertNull(ShuffleSessionStore.fromJson("{\"order\":[]}"));
    }

    @Test
    public void emptyPoolIsHandled() {
        assertTrue(ShuffleOrderFactory.plainOrder(Collections.<Track>emptyList(), 1L).isEmpty());
        assertTrue(ShuffleOrderFactory.smartOrder(null, 1L, null).isEmpty());
        ShuffleSession session = new ShuffleSession("s", 1L, "p", true, 0, 0L, null);
        assertTrue(session.isEmpty());
        assertNull(session.advance());
        assertFalse(session.hasNext());
    }
}
package com.nightlight.app.shuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.playback.PlaybackContext;
import com.nightlight.app.playback.PlaybackContexts;
import com.nightlight.app.playback.PlaybackEngine;
import com.nightlight.app.playback.PlaybackSession;
import com.nightlight.app.playback.PlaybackStartIntent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The runtime invariants of the playback pipeline, asserted directly.
 *
 * <p>Each test pins one rule that used to be violated somewhere in the old
 * implementation. They are deliberately written against the ORDER itself — a
 * {@link List} captured before an action and re-compared after — rather than
 * against incidental output, so a regression shows up as a changed order.
 */
public class QueueInvariantTest {

    // ------------------------------------------------------------- fixtures

    private static Track t(String id, String title, String artist, String album) {
        return new Track(id, title, artist, album, null, "http://s/" + id, 1000L, "2024",
                0L, "english", "pop");
    }

    /** A..H, distinct artists so adjacency rules have room to work. */
    private static List<Track> letters() {
        return new ArrayList<>(Arrays.asList(
                t("a", "Song A", "Artist A", "Album 1"),
                t("b", "Song B", "Artist B", "Album 1"),
                t("c", "Song C", "Artist C", "Album 2"),
                t("d", "Song D", "Artist D", "Album 2"),
                t("e", "Song E", "Artist E", "Album 3"),
                t("f", "Song F", "Artist F", "Album 3"),
                t("g", "Song G", "Artist G", "Album 4"),
                t("h", "Song H", "Artist H", "Album 4")));
    }

    private static List<String> ids(List<Track> tracks) {
        List<String> out = new ArrayList<>();
        for (Track t : tracks) out.add(t.id);
        return out;
    }

    private static PlaybackContext contextOf(List<Track> tracks, int anchorIndex) {
        return PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                tracks.get(anchorIndex),
                com.nightlight.app.playback.PlaybackContextType.SEARCH, "q", tracks, anchorIndex));
    }

    private static QueueManager started(List<Track> tracks, int anchorIndex,
                                        boolean shuffle, boolean smart, long seed) {
        PlaybackContext context = contextOf(tracks, anchorIndex);
        PlaybackSession session = PlaybackEngine.start(context, shuffle, smart,
                Collections.emptyList());
        QueueManager qm = new QueueManager(10);
        qm.beginSession(session);
        qm.startWindow();
        return qm;
    }

    private static final class Collections {
        static <T> java.util.List<T> emptyList() {
            return java.util.Collections.emptyList();
        }
    }

    // ------------------------------------------- INVARIANT 1: next() == cursor

    @Test
    public void invariant1_nextMovesTheCursorAndNeverRegeneratesTheOrder() {
        QueueManager qm = started(letters(), 0, true, false, 42L);
        List<String> order = new ArrayList<>(qm.session().order());

        for (int i = 0; i < 7; i++) {
            String before = qm.session().peekNextId();
            String played = qm.session().advance();
            assertEquals("next() plays exactly the next order entry", before, played);
            assertEquals("next() must not change the order", order, qm.session().order());
        }
    }

    @Test
    public void invariant1_repeatedNextOverManyTapsIsOneStableSequence() {
        QueueManager qm = started(letters(), 0, true, false, 7L);
        List<String> order = new ArrayList<>(qm.session().order());
        List<String> heard = new ArrayList<>();
        heard.add(qm.session().currentId());

        while (!qm.session().isExhausted()) {
            String id = qm.session().advance();
            if (id != null) heard.add(id);
        }
        assertEquals("every tap consumed the same fixed order", order, heard);
        assertEquals(8, new HashSet<>(heard).size());
    }

    // ------------------------------------------- INVARIANT 2: refill == window

    @Test
    public void invariant2_refillAppendsTheSameOrderAndNeverRegeneratesIt() {
        QueueManager qm = new QueueManager(3);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        qm.beginSession(session);
        qm.startWindow();

        List<String> order = new ArrayList<>(session.order());
        int cursorBefore = session.cursor();
        Set<String> appended = new HashSet<>();

        // Force several refills, as repeated QUEUE_LOW events would.
        QueueManager.Refill r1 = qm.refill(0);
        assertTrue(r1.addedAnything());
        appended.addAll(r1.addedIds());
        QueueManager.Refill r2 = qm.refill(r1.addedIds().size());
        appended.addAll(r2.addedIds());

        assertEquals("refill never rewrites the order", order, session.order());
        assertEquals("refill never duplicates a track", appended.size(),
                new HashSet<>(appended).size());
        assertEquals("and refilling is not playing: the cursor does not move",
                cursorBefore, session.cursor());
    }

    // ------------------------------------------- INVARIANT 3: completion

    @Test
    public void invariant3_completingTheQueueDoesNotRegenerateTheOrder() {
        QueueManager qm = new QueueManager(2);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        qm.beginSession(session);
        qm.startWindow();
        List<String> order = new ArrayList<>(session.order());

        // Simulate the player consuming its window, then being topped up again.
        int upcoming = qm.upcoming().size();
        while (!session.isExhausted()) {
            for (int i = 0; i < upcoming && !session.isExhausted(); i++) session.advance();
            upcoming = qm.upcoming().size();
            assertEquals("completion never regenerates the order", order, session.order());
        }
        assertEquals("the whole order was consumed in its original sequence", order, session.order());
        assertTrue(session.isExhausted());
    }

    // ------------------------------------------- INVARIANT 4: state updates

    @Test
    public void invariant4_recomposingTheQueueHasNoSideEffectOnOrderOrCursor() {
        QueueManager qm = started(letters(), 2, true, true, 11L);
        List<String> order = new ArrayList<>(qm.session().order());
        int cursor = qm.session().cursor();

        for (int i = 0; i < 25; i++) {
            qm.composeWindow();
            qm.upcoming();
        }
        assertEquals("UI/state recomposition must not reshuffle", order, qm.session().order());
        assertEquals("UI/state recomposition must not move the cursor", cursor,
                qm.session().cursor());
    }

    // ------------------------------------------- INVARIANT 5: toggle ON

    @Test
    public void invariant5_shuffleToggleOnBuildsOneOrderFromTheFullContext() {
        List<Track> tracks = letters();
        PlaybackContext context = contextOf(tracks, 0);
        PlaybackSession off = PlaybackEngine.start(context, false, false, Collections.emptyList());
        assertEquals("sequential starts at the selection",
                tracks.get(0).id, off.peekNextId());

        Track playing = off.trackById(off.peekNextId());
        PlaybackSession on = PlaybackEngine.restartKeepingTrack(context, playing,
                true, false, Collections.emptyList());

        assertEquals("toggle ON keeps every context track", tracks.size(), on.size());
        assertEquals("toggle ON makes a complete permutation of the whole context",
                new HashSet<>(ids(tracks)), new HashSet<>(on.order()));
        assertEquals("playback continues on the track already playing", playing.id,
                on.peekNextId());
    }

    // ------------------------------------------- INVARIANT 6: toggle OFF

    @Test
    public void invariant6_shuffleToggleOffKeepsTheCurrentTrackAndGoesSequential() {
        List<Track> tracks = letters();
        PlaybackContext context = contextOf(tracks, 0);
        PlaybackSession on = PlaybackEngine.start(context, true, false, Collections.emptyList());
        on.advance();
        Track playing = on.trackById(on.currentId());

        PlaybackSession off = PlaybackEngine.restartKeepingTrack(context, playing,
                false, false, Collections.emptyList());

        assertEquals("the current track keeps playing through the toggle", playing.id,
                off.peekNextId());
        assertFalse("toggle OFF is not a shuffle", off.isShuffle());
        assertEquals("then continues in the context's listed order",
                ids(tracks), off.order());
    }

    // ------------------------------------------- INVARIANT 7: playNext

    @Test
    public void invariant7_playNextInsertsIntoTheManualQueueOnly() {
        QueueManager qm = started(letters(), 2, true, false, 5L);
        List<String> order = new ArrayList<>(qm.session().order());
        int cursor = qm.session().cursor();

        Track x = t("x", "Manual X", "Artist X", "Album X");
        qm.playNext(x);

        assertEquals("Play Next must not touch the session order", order, qm.session().order());
        assertEquals("Play Next must not move the cursor", cursor, qm.session().cursor());
        assertEquals("but it is tracked in the manual queue", 1, qm.manualQueue().size());
        assertFalse("and never becomes a context track", order.contains("x"));
    }

    @Test
    public void phase5_manualItemPlaysRightAfterCurrentThenContextResumes() {
        // Context A B C D E F, cursor at C, Play Next X => C X D E F.
        QueueManager qm = new QueueManager(10);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 2),
                false, false, Collections.emptyList());
        qm.beginSession(session);

        Track x = t("x", "Manual X", "Artist X", "Album X");
        qm.playNext(x);
        List<Track> window = qm.startWindow();

        assertEquals("composition is current, then manual, then context",
                Arrays.asList("c", "x", "d", "e", "f", "g", "h"), ids(window));
        assertEquals("the context order still holds the whole collection",
                Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h"),
                ids(session.context().tracks()));
        assertEquals("and the session plays from the anchor onward", Arrays.asList("c", "d",
                "e", "f", "g", "h"), session.order());

        // startWindow already consumed "c", so the context continues at D.
        assertEquals("context resumes at D after the manual item", "d", session.peekNextId());
    }

    // ------------------------------------------- INVARIANT 8: autoplay anchor

    @Test
    public void invariant8_autoplayDoesNotInheritThePreviousContextsAnchor() {
        QueueManager qm = started(letters(), 3, false, false, 1L);
        PlaybackSession search = qm.session();
        Track seed = search.trackById(search.currentId());

        List<Track> recs = new ArrayList<>(Arrays.asList(
                t("r1", "Rec One", "Artist R1", "Album R"),
                t("r2", "Rec Two", "Artist R2", "Album R")));

        PlaybackContext autoplay = PlaybackContexts.autoplay(recs, seed);
        assertNull("autoplay has no anchor: the seed is not in the recommendations",
                autoplay.anchorTrackId());
        assertEquals("autoplay has its own identity, not the search context's",
                "autoplay:" + seed.id, autoplay.contextId());
        assertEquals(2, autoplay.size());
    }

    // ------------------------------------------- INVARIANT 9: autoplay order

    @Test
    public void invariant9_autoplayStaysSequentialWhenRecommendationShuffleIsOff() {
        List<Track> recs = new ArrayList<>(Arrays.asList(
                t("r1", "Rec One", "Artist R1", "Album R"),
                t("r2", "Rec Two", "Artist R2", "Album R"),
                t("r3", "Rec Three", "Artist R3", "Album R"),
                t("r4", "Rec Four", "Artist R4", "Album R"),
                t("r5", "Rec Five", "Artist R5", "Album R")));

        // Repeat so a random order could not pass by luck.
        for (int run = 0; run < 40; run++) {
            PlaybackContext autoplay = PlaybackContexts.autoplay(recs, null);
            PlaybackSession s = PlaybackSession.sequential(autoplay);
            assertFalse("recommendation shuffle off means no shuffle", s.isShuffle());
            assertEquals("recommendations play in the order they arrived",
                    ids(recs), s.order());
        }
    }

    // ------------------------------------------- INVARIANT 10: gated handover

    @Test
    public void invariant10_contextExhaustsThenHandsOverOnlyWhenAutoplayIsOn() {
        List<Track> tracks = letters();
        QueueManager qm = new QueueManager(4);
        PlaybackSession session = PlaybackEngine.start(contextOf(tracks, 0),
                false, false, Collections.emptyList());
        qm.beginSession(session);

        // Drain the context.
        while (!session.isExhausted()) session.advance();
        assertTrue(session.isExhausted());
        assertNull("an exhausted context offers nothing more to play", session.peekNextId());

        // Autoplay OFF: there is nothing to hand over to, so playback simply ends.
        PlaybackSession stopped = PlaybackEngine.onExhausted(session, false,
                new ArrayList<Track>(), false);
        assertNull("autoplay off means playback stops rather than handing over", stopped);

        // Autoplay ON: hand-over only happens now, with recommendations supplied.
        List<Track> recs = new ArrayList<>(Arrays.asList(
                t("r1", "Rec One", "Artist R1", "Album R"),
                t("r2", "Rec Two", "Artist R2", "Album R")));
        PlaybackSession handOver = PlaybackEngine.onExhausted(session, true, recs, false);

        assertNotNull("autoplay on means a hand-over session is produced", handOver);
        assertEquals(com.nightlight.app.playback.PlaybackContextType.AUTOPLAY,
                handOver.context().type());
        assertEquals(ids(recs), handOver.order());
        assertEquals("the exhausted search order is preserved, not restarted",
                ids(tracks), session.order());
    }

    @Test
    public void invariant10_autoplayOffYieldsNoSessionAtAll() {
        List<Track> tracks = letters();
        PlaybackSession session = PlaybackEngine.start(contextOf(tracks, 0),
                false, false, Collections.emptyList());
        while (!session.isExhausted()) session.advance();

        List<Track> recs = new ArrayList<>(Arrays.asList(t("r1", "Rec One", "Artist R1", "Album R")));
        PlaybackSession none = PlaybackEngine.onExhausted(session, false, recs, false);
        assertNull("with autoplay disabled there must be no hand-over session", none);
    }

    // ------------------------------------------- PHASE 10: refill race

    @Test
    public void phase10_rapidQueueLowAndCompletionBurstProducesExactlyOneRefill() {
        QueueManager qm = new QueueManager(5);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        qm.beginSession(session);
        qm.startWindow();
        List<String> order = new ArrayList<>(session.order());

        // QUEUE_LOW x3 then STATE_ENDED then QUEUE_LOW, all landing together.
        int cursorBefore = session.cursor();
        QueueManager.Refill first = qm.refill(0);
        List<String> appended = new ArrayList<>(first.addedIds());

        // QUEUE_LOW x2, STATE_ENDED, QUEUE_LOW: the player now holds `appended`,
        // so every later event reports the same full window and adds nothing.
        int upcoming = appended.size();
        for (int i = 0; i < 4; i++) {
            QueueManager.Refill later = qm.refill(upcoming);
            assertFalse("a burst of queue events must not append more", later.addedAnything());
        }

        assertEquals("only one logical refill happened", 5, appended.size());
        assertEquals("and it contained no duplicates", appended.size(),
                new HashSet<>(appended).size());
        assertEquals("queuing is not playing, so the cursor never moved",
                cursorBefore, session.cursor());
        assertEquals("the order was never regenerated", order, session.order());
    }

    @Test
    public void phase10_reentrantRefillIsCoalescedRatherThanDuplicated() {
        QueueManager qm = new QueueManager(5);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        qm.beginSession(session);
        qm.startWindow();

        QueueManager.Refill outer = qm.refill(0);
        assertTrue(outer.addedAnything());
        assertEquals(5, outer.addedIds().size());

        // The same burst arriving again with those tracks now queued must be a
        // no-op: refill is idempotent, so N events produce one logical refill.
        assertFalse("re-reporting a now-full window must not append again",
                qm.refill(outer.addedIds().size()).addedAnything());
        assertEquals("refill never played anything on its own", 1, session.cursor());
    }

    // ------------------------------------------- PHASE 11: rapid next

    @Test
    public void phase11_rapidNextKeepsOneSessionAndNoDuplicates() {
        QueueManager qm = started(letters(), 0, true, false, 3L);
        PlaybackSession session = qm.session();
        List<String> order = new ArrayList<>(session.order());
        Set<String> played = new HashSet<>();

        played.add(session.currentId());
        while (!session.isExhausted()) {
            String id = session.advance();
            assertNotNull("each tap advances the cursor one step", id);
            assertTrue("a track never plays twice in one cycle", played.add(id));
            assertEquals("the order never changes under rapid taps", order, session.order());
        }
        assertTrue("exactly one session is serving these taps", session == qm.session());
        assertEquals(8, played.size());
    }

    @Test
    public void phase11_nextPastTheEndStopsRatherThanWrapping() {
        QueueManager qm = new QueueManager(10);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        qm.beginSession(session);
        while (!session.isExhausted()) session.advance();

        assertNull("no wrapping back to the start of the order", session.advance());
        assertEquals(8, session.size());
    }

    // ------------------------------------------- PHASE 12: resume

    @Test
    public void phase12_recomposingAfterAResumeKeepsSessionOrderAndCursor() {
        QueueManager qm = started(letters(), 1, true, true, 99L);
        PlaybackSession session = qm.session();
        session.advance();
        session.advance();

        List<String> order = new ArrayList<>(session.order());
        int cursor = session.cursor();

        // Background/resume: the queue is rebuilt from scratch off the same session.
        QueueManager resumed = new QueueManager(10);
        resumed.beginSession(session);
        resumed.resumeWindow();

        assertEquals("resume must not regenerate the order", order, session.order());
        assertEquals("resume must not move the cursor", cursor, session.cursor());
        assertEquals("and the same tracks remain", order.subList(cursor, order.size()),
                session.remainingIds());
    }

    // ------------------------------------------- PHASE 17: refill seek index

    @Test
    public void phase17_refillReportsWhereNewTracksBeginNotZero() {
        // Regression: STATE_ENDED must play from where the refill appended, not
        // seekTo(0), which would restart the beginning of the window.
        QueueManager qm = new QueueManager(3);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        qm.beginSession(session);

        List<Track> first = qm.startWindow();
        assertEquals(Arrays.asList("a", "b", "c", "d"), ids(first));

        // The player holds 3 of those 4 tracks and is about to run dry.
        int playerQueueSize = 3;
        int upcomingCount = 2;

        QueueManager.Refill r = qm.refill(upcomingCount, playerQueueSize);
        assertTrue("a short window must be topped up", r.addedAnything());
        assertEquals("refill takes the shortfall only", 1, r.addedIds().size());
        assertEquals("refill reports the offset the new track begins at",
                playerQueueSize, r.firstNewIndex());
        assertEquals("and it is the next track in the SAME order", "d", r.addedIds().get(0));

        assertEquals("the old window was not replayed", "a", first.get(0).id);
        assertEquals("the order never changed", Arrays.asList("a", "b", "c", "d", "e", "f",
                "g", "h"), ids(session.context().tracks()));
    }

    @Test
    public void phase17_aFullWindowNeedsNoRefillAndReportsMinusOne() {
        QueueManager qm = new QueueManager(4);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        qm.beginSession(session);
        qm.startWindow();

        QueueManager.Refill r = qm.refill(4, 5);
        assertFalse("a full window must not be topped up", r.addedAnything());
        assertEquals("and the caller is told there is nothing to seek to", -1, r.firstNewIndex());
    }

    // ------------------------------------------- PHASE 3: search context

    @Test
    public void phase3_searchResultPlaysInsideTheSearchContextNotAsASingleTrack() {
        List<Track> coldplay = new ArrayList<>(Arrays.asList(
                t("yellow", "Yellow", "Coldplay", "Parachutes"),
                t("fixyou", "Fix You", "Coldplay", "X&Y"),
                t("paradise", "Paradise", "Coldplay", "Mylo"),
                t("vivalavida", "Viva La Vida", "Coldplay", "Viva"),
                t("scientist", "The Scientist", "Coldplay", "Parachutes"),
                t("clocks", "Clocks", "Coldplay", "Parachutes"),
                t("sparks", "Sparks", "Coldplay", "Parachutes"),
                t("adventure", "Adventure of a Lifetime", "Coldplay", "Parachutes")));

        QueueManager qm = new QueueManager(10);
        PlaybackSession session = PlaybackEngine.start(contextOf(coldplay, 4),
                false, false, Collections.emptyList());
        qm.beginSession(session);

        assertEquals(com.nightlight.app.playback.PlaybackContextType.SEARCH,
                session.context().type());
        List<Track> window = qm.startWindow();
        assertEquals("the tapped result plays first", "scientist", window.get(0).id);
        assertEquals("and playback continues inside the search context",
                Arrays.asList("scientist", "clocks", "sparks", "adventure"), ids(window));
        assertEquals("the search context is not collapsed to one track", 8,
                session.context().size());
        assertEquals("playback continues forward from the tapped result, never wrapping",
                4, session.size());
    }

    // ------------------------------------------- PHASE 6: exhaustion

    @Test
    public void phase6_exhaustedSearchContextDoesNotRestartItself() {
        QueueManager qm = new QueueManager(10);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 2),
                true, false, Collections.emptyList());
        qm.beginSession(session);

        List<String> order = new ArrayList<>(session.order());
        List<String> heard = new ArrayList<>();
        while (!session.isExhausted()) heard.add(session.advance());

        assertEquals("every track played exactly once", order, heard);
        assertTrue(session.isExhausted());
        assertNull("nothing follows an exhausted context", session.peekNextId());
        assertEquals("and the order was never recycled", order, session.order());
    }

    // ------------------------------------------- PHASE 7: single track

    @Test
    public void phase7_singleTrackContextHasNoUpcomingTracks() {
        Track only = t("only", "Blinding Lights", "The Weeknd", "After Hours");
        PlaybackContext single = PlaybackContexts.from(
                PlaybackStartIntent.fromTrack(only));

        assertEquals(com.nightlight.app.playback.PlaybackContextType.SINGLE, single.type());
        assertEquals(1, single.size());

        PlaybackSession session = PlaybackEngine.start(single, false, false,
                Collections.emptyList());
        QueueManager qm = new QueueManager(10);
        qm.beginSession(session);

        List<Track> window = qm.startWindow();
        assertEquals("the single track plays", 1, window.size());
        assertEquals("only", window.get(0).id);
        assertTrue("and the context is immediately exhausted", session.isExhausted());
    }

    // ------------------------------------------- PHASE 8: album

    @Test
    public void phase8_albumShuffleOffPlaysForwardFromTheSelection() {
        List<Track> album = letters();
        PlaybackContext ctx = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                album.get(3),
                com.nightlight.app.playback.PlaybackContextType.ALBUM, "album:1", album, 3));
        PlaybackSession session = PlaybackEngine.start(ctx, false, false, Collections.emptyList());

        assertEquals(com.nightlight.app.playback.PlaybackContextType.ALBUM, session.context().type());
        List<String> heard = new ArrayList<>();
        while (!session.isExhausted()) heard.add(session.advance());
        assertEquals("starts at D and runs to the end", Arrays.asList("d", "e", "f", "g", "h"), heard);
    }

    @Test
    public void phase8_albumShuffleOnIsOneOrderConsumedByRefillToo() {
        List<Track> album = letters();
        PlaybackContext ctx = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                album.get(3),
                com.nightlight.app.playback.PlaybackContextType.ALBUM, "album:1", album, 3));
        QueueManager qm = new QueueManager(2);
        PlaybackSession session = PlaybackEngine.start(ctx, true, false, Collections.emptyList());
        qm.beginSession(session);

        List<String> order = new ArrayList<>(session.order());
        List<String> heard = new ArrayList<>();
        heard.add(session.advance());

        QueueManager.Refill r = qm.refill(0);
        assertEquals("refill reads the same order, it does not reshuffle",
                order.subList(1, 3), r.addedIds());
        for (int i = 0; i < r.addedIds().size(); i++) heard.add(session.advance());

        assertEquals("refill consumed the same order", order, session.order());
        assertEquals("starting at D in the shuffled session", album.get(3).id, heard.get(0));
        assertEquals(3, new HashSet<>(heard).size());
    }

    // ------------------------------------------- PHASE 9: every surface

    @Test
    public void phase9_everySurfaceUsesTheSameSessionArchitecture() {
        com.nightlight.app.playback.PlaybackContextType[] surfaces = {
                com.nightlight.app.playback.PlaybackContextType.PLAYLIST,
                com.nightlight.app.playback.PlaybackContextType.ALBUM,
                com.nightlight.app.playback.PlaybackContextType.ARTIST,
                com.nightlight.app.playback.PlaybackContextType.LIKED,
                com.nightlight.app.playback.PlaybackContextType.SEARCH,
        };
        for (com.nightlight.app.playback.PlaybackContextType type : surfaces) {
            List<Track> tracks = letters();
            PlaybackContext ctx = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                    tracks.get(0), type, type.wire() + ":1", tracks, 0));
            QueueManager qm = new QueueManager(10);
            PlaybackSession session = PlaybackEngine.start(ctx, true, false, Collections.emptyList());
            qm.beginSession(session);

            List<String> order = new ArrayList<>(session.order());
            assertEquals(type + " keeps its type", type, session.context().type());
            assertEquals(type + " covers the whole collection once",
                    new HashSet<>(ids(tracks)), new HashSet<>(order));

            session.advance();
            assertFalse(type + " refill did not regenerate",
                    qm.refill(qm.upcoming().size()).addedAnything());
            assertEquals(type + " refill did not regenerate", order, session.order());
        }
    }

    @Test
    public void phase9_likedSongsShuffleIsOneOrder() {
        List<Track> liked = letters();
        PlaybackContext ctx = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                liked.get(0),
                com.nightlight.app.playback.PlaybackContextType.LIKED, "liked", liked, 0));
        PlaybackSession session = PlaybackEngine.start(ctx, true, false, Collections.emptyList());
        assertEquals(8, session.size());
        assertEquals(8, new HashSet<>(session.order()).size());
    }

    // ------------------------------------------- PHASE 13: failure isolation

    @Test
    public void phase13_emptyRecommendationBatchFailsGracefully() {
        QueueManager qm = new QueueManager(10);
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        qm.beginSession(session);
        while (!session.isExhausted()) session.advance();

        PlaybackSession none = PlaybackEngine.onExhausted(session, true,
                new ArrayList<Track>(), false);
        assertNull("a failed recommendation fetch yields no session and no crash", none);
        assertEquals("and the exhausted order is left alone", 8, session.size());
    }

    @Test
    public void phase13_nullRecommendationBatchIsSafe() {
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        while (!session.isExhausted()) session.advance();
        PlaybackSession none = PlaybackEngine.onExhausted(session, true, null, false);
        assertNull("a null recommendation batch must not crash the hand-over", none);
    }

    // ------------------------------------------- cursor sync both directions

    @Test
    public void previousTapsSyncTheCursorBackwardsWithoutChangingTheOrder() {
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                true, false, Collections.emptyList());
        List<String> order = new ArrayList<>(session.order());

        session.advance();
        session.advance();
        String second = session.currentId();

        assertTrue("going back to a track in the order must sync", session.syncCursorTo(second));
        assertEquals("cursor moved back one step", second, session.currentId());
        assertEquals("the order is untouched by stepping backwards", order, session.order());
        assertFalse("a stepped-back track is playable again",
                session.playedIds().contains(session.peekIdAt(0)));

        assertTrue(session.syncCursorTo(order.get(4)));
        assertEquals(order.get(4), session.currentId());
        assertEquals("still one unchanged order", order, session.order());
    }

    @Test
    public void cursorSyncIgnoresTracksOutsideTheOrder() {
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        session.advance();
        int cursor = session.cursor();

        assertFalse("a manual item is not in the order",
                session.syncCursorTo("x"));
        assertEquals("and the cursor did not move", cursor, session.cursor());
    }

    @Test
    public void cursorSyncForwardMarksSkippedTracksPlayed() {
        PlaybackSession session = PlaybackEngine.start(contextOf(letters(), 0),
                false, false, Collections.emptyList());
        List<String> order = new ArrayList<>(session.order());

        assertTrue(session.syncCursorTo(order.get(3)));
        assertEquals(order.get(3), session.currentId());
        assertEquals("everything up to and including it counts as played", 4,
                session.playedIds().size());
        assertEquals("the order is unchanged", order, session.order());
    }
}

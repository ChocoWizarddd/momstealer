package com.nightlight.app.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The universal context contract: any surface can start playback, the context
 * is predictable, and the queue only ever consumes the one active order.
 */
public class PlaybackContextTest {

    private static Track t(String id, String title, String artist) {
        return new Track(id, title, artist, "Album " + id, null, "http://s/" + id, 1000L, "2024",
                0L, "english", "pop");
    }

    /** The spec's Coldplay example: eight results in a fixed listed order. */
    private static List<Track> coldplay() {
        return new ArrayList<>(Arrays.asList(
                t("yellow", "Yellow", "Coldplay"),
                t("fixyou", "Fix You", "Coldplay"),
                t("paradise", "Paradise", "Coldplay"),
                t("vivalavida", "Viva La Vida", "Coldplay"),
                t("scientist", "The Scientist", "Coldplay"),
                t("clocks", "Clocks", "Coldplay"),
                t("sparks", "Sparks", "Coldplay"),
                t("adventure", "Adventure of a Lifetime", "Coldplay")));
    }

    private static List<Track> lists(int n) {
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(t("rec" + i, "Rec " + i, "Artist " + (i % 2)));
        return out;
    }

    private static List<String> ids(List<Track> tracks) {
        List<String> out = new ArrayList<>();
        for (Track t : tracks) out.add(t.id);
        return out;
    }

    // ------------------------------------------------------------- resolution

    @Test
    public void tappingASearchResultCreatesASearchContext() {
        PlaybackStartIntent intent = PlaybackStartIntent.fromCollection(
                t("scientist", "The Scientist", "Coldplay"),
                PlaybackContextType.SEARCH, "coldplay", coldplay(), 4);

        PlaybackContext context = PlaybackContexts.from(intent);
        assertEquals(PlaybackContextType.SEARCH, context.type());
        assertEquals("coldplay", context.contextId());
        assertEquals(8, context.size());
        assertEquals("scientist", context.anchorTrackId());
        assertEquals(4, context.anchorIndex());
        assertEquals("search:coldplay", context.sourceKey());
    }

    @Test
    public void tappingATrackInAListIsNotDegradedToSingle() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("c", "C", "Artist"), PlaybackContextType.ALBUM, "album:1",
                Arrays.asList(t("a", "A", "Artist"), t("b", "B", "Artist"), t("c", "C", "Artist"),
                        t("d", "D", "Artist")), 2));
        assertEquals(PlaybackContextType.ALBUM, context.type());
        assertEquals(4, context.size());
    }

    @Test
    public void isolatedPlayResolvesToSingle() {
        PlaybackContext context = PlaybackContexts.from(
                PlaybackStartIntent.single(t("blinding", "Blinding Lights", "The Weeknd")));
        assertEquals(PlaybackContextType.SINGLE, context.type());
        assertEquals(1, context.size());
        assertEquals("single:blinding", context.contextId());
    }

    @Test
    public void collectionTypeWithNoCollectionDegradesToSingle() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("x", "X", "Artist"), PlaybackContextType.PLAYLIST, "playlist:1",
                Collections.singletonList(t("x", "X", "Artist")), 0));
        assertEquals(PlaybackContextType.SINGLE, context.type());
    }

    @Test
    public void everyRequiredContextTypeExists() {
        List<PlaybackContextType> required = Arrays.asList(
                PlaybackContextType.PLAYLIST, PlaybackContextType.ALBUM, PlaybackContextType.ARTIST,
                PlaybackContextType.SEARCH, PlaybackContextType.LIKED, PlaybackContextType.LIBRARY,
                PlaybackContextType.SINGLE, PlaybackContextType.RECOMMENDATION,
                PlaybackContextType.AUTOPLAY);
        for (PlaybackContextType type : required) {
            assertNotNull(type.wire());
            assertFalse(type.wire().isEmpty());
        }
        assertEquals(9, required.size());
    }

    @Test
    public void contextKeepsTheNaturalOrderOfTheSource() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("c", "C", "Artist"), PlaybackContextType.SEARCH, "search:x",
                Arrays.asList(t("a", "A", "Artist"), t("b", "B", "Artist"), t("c", "C", "Artist"),
                        t("d", "D", "Artist"), t("e", "E", "Artist"), t("f", "F", "Artist")), 2));
        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f"), ids(context.tracks()));
    }

    // -------------------------------------------------- shuffle off: continue

    @Test
    public void searchWithoutShuffleContinuesForwardFromTheSelection() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("c", "Song C", "Artist"), PlaybackContextType.SEARCH, "search:x",
                Arrays.asList(t("a", "Song A", "Artist"), t("b", "Song B", "Artist"),
                        t("c", "Song C", "Artist"), t("d", "Song D", "Artist"),
                        t("e", "Song E", "Artist"), t("f", "Song F", "Artist")), 2));

        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        assertEquals("c", session.advance());
        assertEquals("d", session.advance());
        assertEquals("e", session.advance());
        assertEquals("f", session.advance());
        assertTrue(session.isExhausted());
        assertNull("must not wrap back to the tracks above the selection", session.advance());
    }

    @Test
    public void albumPlaybackContinuesForwardFromTheTappedTrack() {
        List<Track> album = Arrays.asList(t("a", "A", "X"), t("b", "B", "X"), t("c", "C", "X"),
                t("d", "D", "X"), t("e", "E", "X"), t("f", "F", "X"), t("g", "G", "X"));
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                album.get(3), PlaybackContextType.ALBUM, "album:1", album, 3));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        assertEquals(Arrays.asList("d", "e", "f", "g"), session.order());
    }

    // --------------------------------------------------- shuffle on: rotate

    @Test
    public void shuffleStartsOnTheSelectedTrackAndKeepsTheOrder() {
        List<Track> results = new ArrayList<>();
        for (char c = 'A'; c <= 'J'; c++) results.add(t(String.valueOf(c), "Song " + c, "Artist " + c));
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                results.get(3), PlaybackContextType.SEARCH, "search:x", results, 3));

        PlaybackSession session = PlaybackEngine.start(context, true, false, null);
        assertEquals("the order must begin at the user's pick", "D", session.peekNextId());
        assertEquals("D", session.advance());

        List<String> rest = session.remaining();
        assertEquals(9, rest.size());
        assertEquals(new HashSet<>(ids(results)), new HashSet<>(session.order()));
        assertFalse(rest.contains("D"));
    }

    @Test
    public void shuffleDoesNotRegenerateWhenPlaybackStartsFromTheSelection() {
        List<Track> results = new ArrayList<>();
        for (char c = 'A'; c <= 'J'; c++) results.add(t(String.valueOf(c), "Song " + c, "Artist " + c));
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                results.get(3), PlaybackContextType.SEARCH, "search:x", results, 3));

        PlaybackSession session = PlaybackEngine.start(context, true, false, null);
        List<String> order = new ArrayList<>(session.order());
        for (int i = 0; i < 10; i++) {
            assertEquals(order.get(i), session.advance());
            assertEquals("order must stay frozen", order, session.order());
        }
        assertTrue(session.isExhausted());
    }

    @Test
    public void rotatedOrderStillPlaysEveryTrackExactlyOnce() {
        for (int anchor = 0; anchor < 8; anchor++) {
            PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                    coldplay().get(anchor), PlaybackContextType.ALBUM, "album:1", coldplay(), anchor));
            PlaybackSession session = PlaybackEngine.start(context, true, false, null);
            assertEquals(coldplay().get(anchor).id, session.peekNextId());
            assertEquals(8, session.order().size());
            assertEquals(8, new HashSet<>(session.order()).size());
        }
    }

    @Test
    public void rotateToIsOrderPreserving() {
        List<String> order = Arrays.asList("a", "b", "c", "d", "e");
        assertEquals(Arrays.asList("c", "d", "e", "a", "b"), PlaybackSession.rotateTo(order, "c"));
        assertEquals(order, PlaybackSession.rotateTo(order, "a"));
        assertEquals(order, PlaybackSession.rotateTo(order, "zz"));
    }

    // ----------------------------------------------------------------- refill

    @Test
    public void refillContinuesTheContextWithoutRestartingOrDuplicating() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("c", "Song C", "Artist"), PlaybackContextType.SEARCH, "search:x",
                Arrays.asList(t("a", "A", "Artist"), t("b", "B", "Artist"), t("c", "C", "Artist"),
                        t("d", "D", "Artist"), t("e", "E", "Artist"), t("f", "F", "Artist"),
                        t("g", "G", "Artist"), t("h", "H", "Artist"),
                        t("i", "I", "Artist"), t("j", "J", "Artist"),
                        t("k", "K", "Artist"), t("l", "L", "Artist"),
                        t("m", "M", "Artist"), t("n", "N", "Artist"),
                        t("o", "O", "Artist")), 2));

        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        assertEquals("c", session.advance());

        List<String> queued = new ArrayList<>();
        queued.add("c");
        queued.addAll(PlaybackEngine.refill(session, queued, 5));
        assertEquals(Arrays.asList("c", "d", "e", "f", "g", "h"), queued);

        for (int i = 0; i < 5; i++) {
            String played = session.advance();
            queued.remove(played);
            queued.addAll(PlaybackEngine.refill(session, queued, 5));
            assertFalse("played track re-queued: " + played, queued.contains(played));
            assertEquals(6, queued.size());
        }
        assertEquals("no track plays twice in a cycle", 6, new HashSet<>(session.playedIds()).size());
    }

    @Test
    public void refillIsIdempotent() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(4), PlaybackContextType.SEARCH, "search:coldplay", coldplay(), 4));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        session.advance();

        List<String> queued = new ArrayList<>();
        queued.addAll(PlaybackEngine.refill(session, queued, 4));
        assertEquals("only the tracks left in the context can be queued", 3, queued.size());
        assertTrue(PlaybackEngine.refill(session, queued, 4).isEmpty());
    }

    @Test
    public void refillNeverInventsTracksOutsideTheContext() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(0), PlaybackContextType.ALBUM, "album:1", coldplay(), 0));
        PlaybackSession session = PlaybackEngine.start(context, true, false, null);
        session.advance();
        List<String> queued = new ArrayList<>();
        queued.addAll(PlaybackEngine.refill(session, queued, 50));
        assertEquals("a context of 8 can only ever fill 7 upcoming slots", 7, queued.size());
        assertTrue("queue must only contain context tracks",
                new HashSet<>(ids(coldplay())).containsAll(queued));
        assertEquals("no duplicates", queued.size(), new HashSet<>(queued).size());
        assertFalse(queued.contains("yellow"));
    }

    // --------------------------------------------------------------- autoplay

    @Test
    public void exhaustedContextHandsOverToAutoplayWhenEnabled() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.single(
                t("blinding", "Blinding Lights", "The Weeknd")));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        assertEquals("blinding", session.advance());
        assertTrue(session.isExhausted());

        PlaybackSession autoplay = PlaybackEngine.onExhausted(session, true,
                Arrays.asList(t("afterhours", "After Hours", "The Weeknd"),
                        t("savetyears", "Save Your Tears", "The Weeknd"),
                        t("starboy", "Starboy", "The Weeknd")), false);

        assertNotNull(autoplay);
        assertEquals(PlaybackContextType.AUTOPLAY, autoplay.context().type());
        assertEquals("afterhours", autoplay.peekNextId());
        assertEquals(3, autoplay.order().size());
    }

    @Test
    public void exhaustedContextStopsWhenAutoplayIsOff() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(2), PlaybackContextType.SEARCH, "search:coldplay", coldplay(), 2));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        while (session.advance() != null) { }
        assertTrue(session.isExhausted());
        assertNull(PlaybackEngine.onExhausted(session, false, Arrays.asList(
                t("r1", "R1", "Artist"), t("r2", "R2", "Artist")), false));
    }

    @Test
    public void autoplayWithNoCandidatesDoesNotStart() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.single(t("x", "X", "Y")));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        session.advance();
        assertNull(PlaybackEngine.onExhausted(session, true, new ArrayList<Track>(), false));
        assertNull(PlaybackEngine.onExhausted(session, true, null, false));
    }

    @Test
    public void autoplayContextDoesNotHandOverToItself() {
        PlaybackContext autoplay = PlaybackContexts.autoplay(
                Arrays.asList(t("r1", "R1", "A"), t("r2", "R2", "A")), t("seed", "Seed", "S"));
        PlaybackSession session = PlaybackEngine.start(autoplay, false, false, null);
        while (session.advance() != null) { }
        assertNull(PlaybackEngine.onExhausted(session, true,
                Arrays.asList(t("r3", "R3", "A")), false));
    }

    @Test
    public void recommendationsAreASeparateLayerAndNeverMutateTheContext() {
        List<String> contextOrder = Arrays.asList("a", "b", "c", "d");
        List<String> recs = Arrays.asList("r1", "b", "r2");
        List<String> layer = PlaybackEngine.recommendationLayer(contextOrder, recs);
        assertEquals(Arrays.asList("r1", "r2"), layer);
        assertEquals("context order untouched", Arrays.asList("a", "b", "c", "d"), contextOrder);
    }

    // ------------------------------------------------------- context identity

    @Test
    public void differentSourcesProduceDifferentContextKeys() {
        PlaybackContext search = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("a", "A", "X"), PlaybackContextType.SEARCH, "search:daft punk",
                Arrays.asList(t("a", "A", "X"), t("b", "B", "X")), 0));
        PlaybackContext liked = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                t("a", "A", "X"), PlaybackContextType.LIKED, "liked",
                Arrays.asList(t("a", "A", "X"), t("b", "B", "X")), 0));
        assertFalse(search.sourceKey().equals(liked.sourceKey()));
    }

    @Test
    public void startingANewContextDoesNotMutateTheOldSession() {
        PlaybackContext first = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(0), PlaybackContextType.PLAYLIST, "playlist:A", coldplay(), 0));
        PlaybackSession session = PlaybackEngine.start(first, true, false, null);
        List<String> order = new ArrayList<>(session.order());
        session.advance();

        PlaybackContext second = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(4), PlaybackContextType.SEARCH, "search:coldplay", coldplay(), 4));
        PlaybackEngine.start(second, true, false, null);

        assertEquals("the previous session is untouched by a new playback start",
                order, session.order());
        assertEquals(1, session.cursor());
    }

    @Test
    public void smartShuffleAlsoStartsOnTheSelectedTrack() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(4), PlaybackContextType.SEARCH, "search:coldplay", coldplay(), 4));
        PlaybackSession session = PlaybackEngine.start(context, true, true, null);
        assertEquals("scientist", session.peekNextId());
        assertTrue(session.isShuffle());
        assertEquals(8, new HashSet<>(session.order()).size());
    }

    @Test
    public void togglingShuffleRebuildsFromTheWholeContextNotTheWindow() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(4), PlaybackContextType.SEARCH, "coldplay", coldplay(), 4));

        PlaybackSession sequential = PlaybackEngine.start(context, false, false, null);
        sequential.advance();
        Track playing = sequential.trackById(sequential.currentId());

        PlaybackSession toggled = PlaybackEngine.restartKeepingTrack(
                context, playing, true, false, null);

        assertEquals("a shuffle toggle must not lose the rest of the context",
                8, toggled.context().size());
        assertEquals("playback continues from the track that was already playing",
                playing.id, toggled.peekNextId());
    }

    @Test
    public void togglingShuffleOffKeepsTheCurrentlyPlayingTrack() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(0), PlaybackContextType.ALBUM, "album:parachutes", coldplay(), 0));

        PlaybackSession shuffled = PlaybackEngine.start(context, true, false, null);
        shuffled.advance();
        Track playing = shuffled.trackById(shuffled.currentId());

        PlaybackSession toggled = PlaybackEngine.restartKeepingTrack(
                context, playing, false, false, null);

        assertEquals(playing.id, toggled.peekNextId());
        assertTrue("shuffle off means the natural listed order", !toggled.isShuffle());
    }

    @Test
    public void autoplayHandOverDoesNotDisturbTheExhaustedContext() {
        PlaybackContext context = PlaybackContexts.from(PlaybackStartIntent.fromCollection(
                coldplay().get(0), PlaybackContextType.SEARCH, "coldplay", coldplay(), 0));
        PlaybackSession session = PlaybackEngine.start(context, false, false, null);
        List<String> order = new ArrayList<>(session.order());
        while (!session.isExhausted()) session.advance();

        PlaybackContext autoplay = PlaybackContexts.autoplay(lists(3), session.trackById(session.currentId()));
        PlaybackSession handOver = PlaybackSession.create(autoplay, false, 1L, null);

        assertFalse("autoplay is a destination, it never hands over to itself",
                handOver.context().type().handsOverToAutoplay());
        assertTrue("the context that ran out is the one that hands over",
                context.type().handsOverToAutoplay());
        assertEquals("the exhausted context keeps its finished order", order, session.order());
        assertEquals(3, handOver.context().size());
    }
}

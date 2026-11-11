package com.nightlight.app.ui.common;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Verifies TrackPlayer.dedupeVariants: same-title variants by the SAME artist
 * collapse, but different artists' recordings of the same title must survive
 * (a search like "imagine" returns many "Imagine" covers by different artists;
 * collapsing them all would leave a one-track queue).
 */
public class TrackPlayerTest {

    private static Track track(String id, String name, String artists) {
        return new Track(id, name, artists, "album", "img", "https://x/y.mp3", 100_000L, "2020");
    }

    @Test
    public void keepsDifferentArtistsWithSameTitle() {
        List<Track> input = new ArrayList<>();
        input.add(track("a", "Imagine", "MIXBYDOLCE, Watan Sahi"));
        input.add(track("b", "Imagine", "Wolfgang Tinder"));
        input.add(track("c", "Imagine", "Milky Bay"));
        input.add(track("d", "Imagine", "Spiritual Flute Music"));

        List<Track> out = TrackPlayer.dedupeVariants(input);

        assertEquals(4, out.size());
    }

    @Test
    public void collapsesSameArtistVariantsOfSameTitle() {
        List<Track> input = new ArrayList<>();
        input.add(track("a", "Believer (Imagine Dragons cover)", "Polina Cherkas"));
        input.add(track("b", "Believer (Acoustic Version)", "Polina Cherkas"));
        input.add(track("c", "Believer (Live)", "Polina Cherkas"));

        List<Track> out = TrackPlayer.dedupeVariants(input);

        assertEquals(1, out.size());
    }

    @Test
    public void collapsesExactDuplicates() {
        List<Track> input = new ArrayList<>();
        input.add(track("a", "Imagine", "MIXBYDOLCE"));
        input.add(track("b", "Imagine", "MIXBYDOLCE"));

        List<Track> out = TrackPlayer.dedupeVariants(input);

        assertEquals(1, out.size());
    }

    @Test
    public void handlesMixedBatchLikeSearchResults() {
        List<Track> input = new ArrayList<>();
        // Five "Imagine" rows by the same artist + one by another artist.
        input.add(track("a", "Imagine", "MIXBYDOLCE, Watan Sahi"));
        input.add(track("b", "Imagine", "MIXBYDOLCE, Watan Sahi"));
        input.add(track("c", "Imagine", "MIXBYDOLCE, Watan Sahi"));
        input.add(track("d", "Imagine", "Wolfgang Tinder"));
        input.add(track("e", "Watan Sahi", "MIXBYDOLCE"));
        input.add(track("f", "Wrecked (16-Bit Imagine Dragons Emulation)", "Arcade Player"));

        List<Track> out = TrackPlayer.dedupeVariants(input);

        assertEquals(4, out.size());
    }

    @Test
    public void nullAndSmallListsAreUnchanged() {
        assertEquals(null, TrackPlayer.dedupeVariants(null));

        List<Track> one = new ArrayList<>();
        one.add(track("a", "Imagine", "MIXBYDOLCE"));
        assertEquals(one, TrackPlayer.dedupeVariants(one));
    }
}
package com.nightlight.app.smartshuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

public class ListeningContextTest {

    private static Track track(String id, String name, String artist, String album) {
        return new Track(id, name, artist, album, "", null, 200_000L, "2024");
    }

    @Test
    public void nullSeed_givesEmptyContext() {
        ListeningContext ctx = ListeningContext.fromTrack(null);
        assertNull(ctx.primaryMood);
        assertEquals(0.0, ctx.moodConfidence, 0.001);
    }

    @Test
    public void loveKeywords_detectLoveWeakly() {
        ListeningContext ctx = ListeningContext.fromTrack(track("1", "Dil Se", "Artist", "Album"));
        assertEquals(ListeningContext.LOVE, ctx.primaryMood);
        // Weak evidence, never authoritative.
        assertTrue(ctx.moodConfidence <= 0.5);
        assertTrue(ctx.moodConfidence >= 0.05);
    }

    @Test
    public void neutralText_detectsNothing() {
        ListeningContext ctx = ListeningContext.fromTrack(track("2", "Concrete Jungle", "Band", "Album"));
        assertNull(ctx.primaryMood);
    }

    @Test
    public void explicitMood_hasHighStableConfidence() {
        Track seed = track("3", "Anything", "Anyone", "Anywhere");
        ListeningContext ctx = ListeningContext.explicit(ListeningContext.LOVE, seed);
        assertEquals(ListeningContext.LOVE, ctx.primaryMood);
        assertTrue(ctx.moodConfidence >= 0.8);
    }

    @Test
    public void explicitUnknownMood_fallsBackToInference() {
        Track seed = track("4", "Dil Se", "Artist", "Album");
        ListeningContext ctx = ListeningContext.explicit("not-a-mood", seed);
        assertEquals(ListeningContext.LOVE, ctx.primaryMood);
    }

    @Test
    public void normalization_stripsParensPunctuationAndCase() {
        assertEquals("believer imagine dragons", ListeningContext.norm("Believer (Official Audio) — Imagine Dragons"));
        assertEquals("tere bina", ListeningContext.norm("  Tere   Bina  "));
    }

    @Test
    public void blends_includeLoveFamily() {
        assertNotNull(ListeningContext.BLENDS.get(ListeningContext.LOVE));
        assertEquals(1.0, ListeningContext.BLENDS.get(ListeningContext.LOVE)
                .get(ListeningContext.LOVE), 0.001);
        assertTrue(ListeningContext.BLENDS.get(ListeningContext.LOVE)
                .containsKey(ListeningContext.ROMANTIC));
        assertTrue(ListeningContext.BLENDS.get(ListeningContext.ENERGY)
                .containsKey(ListeningContext.WORKOUT));
    }
}
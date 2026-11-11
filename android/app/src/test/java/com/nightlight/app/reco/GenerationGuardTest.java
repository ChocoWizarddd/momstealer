package com.nightlight.app.reco;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Queue/concurrency contracts (§26 J–M): single-flight generation, stale
 * response discard, top-up exclusivity, session reset, state transitions.
 * Pure guard logic — PlaybackManager wires it to Media3/radio callbacks.
 */
public class GenerationGuardTest {

    @Test
    public void singleFlight_secondAcquireFails() {
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        long first = g.startGeneration(s);
        assertTrue(first >= 0);
        assertEquals("second acquire while busy fails", -1, g.startGeneration(s));
        assertTrue(g.isGenerating());
        assertTrue(g.finishGeneration(s, first, true));
        assertFalse(g.isGenerating());
        long second = g.startGeneration(s);
        assertTrue(second > first);
    }

    @Test
    public void staleSession_rejected() {
        GenerationGuard g = new GenerationGuard();
        String s1 = g.beginSession();
        long gen = g.startGeneration(s1);
        assertTrue(gen >= 0);
        String s2 = g.beginSession(); // new session invalidates the old
        assertEquals(-1, g.startGeneration(s1));
        assertFalse(g.isCurrent(s1, gen));
        assertFalse("stale finish touches nothing", g.finishGeneration(s1, gen, true));
        assertEquals(GenerationGuard.State.IDLE, g.state());
        assertTrue(g.startGeneration(s2) >= 0);
    }

    @Test
    public void staleGeneration_finishIgnored() {
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        long gen = g.startGeneration(s);
        assertFalse("wrong gen discarded", g.finishGeneration(s, gen + 99, true));
        assertTrue("flight still owned", g.isGenerating());
        assertTrue(g.finishGeneration(s, gen, true));
    }

    @Test
    public void reset_invalidatesEverything() {
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        long gen = g.startGeneration(s);
        g.reset();
        assertEquals(GenerationGuard.State.IDLE, g.state());
        assertFalse(g.isGenerating());
        assertFalse(g.isCurrent(s, gen));
        assertEquals(-1, g.startGeneration(s));
    }

    @Test
    public void stateTransitions_idleGeneratingReadyPlayingTopUp() {
        GenerationGuard g = new GenerationGuard();
        assertEquals(GenerationGuard.State.IDLE, g.state());
        String s = g.beginSession();
        long gen = g.startGeneration(s);
        assertEquals(GenerationGuard.State.GENERATING, g.state());
        assertTrue(g.finishGeneration(s, gen, false));
        assertEquals(GenerationGuard.State.READY, g.state());
        g.onPlaying(s);
        assertEquals(GenerationGuard.State.PLAYING, g.state());
        long top = g.startGeneration(s);
        assertEquals(GenerationGuard.State.TOPPING_UP, g.state());
        assertTrue(g.finishGeneration(s, top, true));
        assertEquals(GenerationGuard.State.PLAYING, g.state());
    }

    @Test
    public void error_isRecoverable_notTerminal() {
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        long gen = g.startGeneration(s);
        g.markError(s, gen);
        assertEquals(GenerationGuard.State.ERROR_RECOVERABLE, g.state());
        assertFalse(g.isGenerating());
        // Next top-up may proceed after a recoverable error.
        assertTrue(g.startGeneration(s) >= 0);
    }

    @Test
    public void forceRelease_unwedgesStuckFlight() {
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        g.startGeneration(s);
        g.forceRelease(s);
        assertFalse(g.isGenerating());
        assertEquals(GenerationGuard.State.ERROR_RECOVERABLE, g.state());
        g.forceRelease("other-session");
        assertEquals(GenerationGuard.State.ERROR_RECOVERABLE, g.state());
    }

    @Test
    public void rapidNext_singleGenerationSurvives() {
        // Simulates rapid Next taps: first acquire wins, the rest back off,
        // and only the owner's finish lands.
        GenerationGuard g = new GenerationGuard();
        String s = g.beginSession();
        int wins = 0;
        for (int i = 0; i < 10; i++) {
            if (g.startGeneration(s) >= 0) wins++;
        }
        assertEquals("exactly one generation across 10 rapid taps", 1, wins);
    }
}

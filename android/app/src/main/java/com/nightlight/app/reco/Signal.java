package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;

/**
 * Graded implicit-feedback outcomes. Every playback event maps to exactly one
 * signal with a signed weight; weights are ordered by behavioral strength
 * (see PlaybackSignalsTest — like > full > 80% > 50% > partial > late skip >
 * early skip; unlike strongly negative) and treated as v1 deterministic
 * constants, NOT tuned per-test.
 */
public enum Signal {
    EARLY_SKIP(-1.0),      // user skip before 10% listened: strong negative
    SKIP(-0.4),            // user skip at 10-40%: negative
    PARTIAL(-0.1),         // user skip at 40-80%: near-neutral, slight negative
    COMPLETED_50(0.3),     // reached 50%: positive
    COMPLETED_80(0.6),     // reached 80%: stronger positive
    FULL(1.0),             // played to the end (auto or manual): strong positive
    REPLAY(1.2),           // same track started again in-session: very positive
    LIKE(1.5),             // explicit like: strongest positive
    UNLIKE(-1.0),          // explicit unlike: strong negative
    QUEUE_ADD(0.5),        // user queued / play-next: positive intent
    MANUAL_SELECT(0.8);    // user tapped the track: strong positive intent

    public final double weight;

    Signal(double weight) {
        this.weight = weight;
    }

    /**
     * Maps a finished playback stretch to a signal from the listened fraction
     * (0..1 of track duration) and how it ended.
     *
     * @param fraction listened fraction, clamped 0..1
     * @param userSkipped true when the user pressed next/stopped early
     * @param replay true when this same track id already played this session
     */
    public static Signal fromPlayback(double fraction, boolean userSkipped, boolean replay) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        if (replay && f >= 0.1) return REPLAY;
        if (userSkipped) {
            if (f < 0.10) return EARLY_SKIP;
            if (f < 0.40) return SKIP;
            if (f < 0.80) return PARTIAL;
            return COMPLETED_80; // skipped at the tail: still a completion
        }
        if (f >= 0.98) return FULL;
        if (f >= 0.80) return COMPLETED_80;
        if (f >= 0.50) return COMPLETED_50;
        if (f >= 0.10) return PARTIAL;
        return EARLY_SKIP;
    }

    /** True for positive engagement worth reinforcing in profiles. */
    public boolean isSuccess() {
        return weight >= COMPLETED_50.weight;
    }

    public static String describeFor(Track t) {
        return t == null ? "?" : t.name + " — " + t.artists;
    }
}

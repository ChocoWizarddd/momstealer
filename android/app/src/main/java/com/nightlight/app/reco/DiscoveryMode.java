package com.nightlight.app.reco;

/**
 * Familiar / Balanced / Discovery (§15). Discovery is relevance-broadened,
 * never random injection: the scorer rewards related-genre and exploration
 * sources, and MMR lambda drops so variety wins more ties.
 */
public enum DiscoveryMode {
    FAMILIAR(0.85, 0.4),
    BALANCED(0.70, 1.0),
    DISCOVERY(0.50, 1.6);

    /** MMR lambda: higher = relevance dominates (Carbonell & Goldstein). */
    public final double mmrLambda;
    /** Novelty multiplier for unplayed candidates. */
    public final double novelty;

    DiscoveryMode(double mmrLambda, double novelty) {
        this.mmrLambda = mmrLambda;
        this.novelty = novelty;
    }

    public double noveltyWeight() {
        return novelty;
    }

    /** Maps the legacy continuous discovery ratio to a named mode. */
    public static DiscoveryMode fromRatio(double ratio) {
        if (ratio <= 0.10) return FAMILIAR;
        if (ratio >= 0.35) return DISCOVERY;
        return BALANCED;
    }
}

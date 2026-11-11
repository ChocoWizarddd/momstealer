package com.nightlight.app.reco;

/**
 * Cached audio-feature snapshot for one track (§10). Every field is nullable:
 * UNKNOWN stays UNKNOWN — never zero-filled, never guessed. Confidence
 * reflects how much of the snapshot is actually known.
 */
public final class TrackFeatures {

    public final Double tempo;
    public final Double energy;
    public final Double danceability;
    public final Double valence;
    public final Double acousticness;
    public final Double instrumentalness;
    public final Double speechiness;
    public final Double liveness;
    public final Double loudness;
    public final Integer key;

    private TrackFeatures(Builder b) {
        this.tempo = b.tempo;
        this.energy = b.energy;
        this.danceability = b.danceability;
        this.valence = b.valence;
        this.acousticness = b.acousticness;
        this.instrumentalness = b.instrumentalness;
        this.speechiness = b.speechiness;
        this.liveness = b.liveness;
        this.loudness = b.loudness;
        this.key = b.key;
    }

    /** Fraction of dimensions actually known (0..1). */
    public double confidence() {
        int known = 0;
        if (tempo != null) known++;
        if (energy != null) known++;
        if (danceability != null) known++;
        if (valence != null) known++;
        if (acousticness != null) known++;
        if (instrumentalness != null) known++;
        if (speechiness != null) known++;
        if (liveness != null) known++;
        if (loudness != null) known++;
        if (key != null && key >= 0) known++;
        return known / 10.0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TrackFeatures unknown() {
        return new Builder().build();
    }

    public static final class Builder {
        Double tempo;
        Double energy;
        Double danceability;
        Double valence;
        Double acousticness;
        Double instrumentalness;
        Double speechiness;
        Double liveness;
        Double loudness;
        Integer key;

        public Builder tempo(Double v) {
            this.tempo = v;
            return this;
        }

        public Builder energy(Double v) {
            this.energy = v;
            return this;
        }

        public Builder danceability(Double v) {
            this.danceability = v;
            return this;
        }

        public Builder valence(Double v) {
            this.valence = v;
            return this;
        }

        public Builder acousticness(Double v) {
            this.acousticness = v;
            return this;
        }

        public Builder instrumentalness(Double v) {
            this.instrumentalness = v;
            return this;
        }

        public Builder speechiness(Double v) {
            this.speechiness = v;
            return this;
        }

        public Builder liveness(Double v) {
            this.liveness = v;
            return this;
        }

        public Builder loudness(Double v) {
            this.loudness = v;
            return this;
        }

        public Builder key(Integer v) {
            this.key = v;
            return this;
        }

        public TrackFeatures build() {
            return new TrackFeatures(this);
        }
    }
}

package com.nightlight.app.reco;

/**
 * Normalized audio similarity between two feature snapshots (§11).
 *
 * Only dimensions known on BOTH sides participate; unknown fields are
 * excluded (never zero-filled). Objective: a COMPATIBLE transition, not an
 * identical song — small tempo/energy/mood movement scores highly.
 *
 * Ranges normalized before comparing (never assume identical scales):
 * tempo 0–250 BPM, loudness −60–0 dB, key circular 0–11, rest 0–1.
 */
public final class AudioSimilarity {

    private AudioSimilarity() {
    }

    public static final class Result {
        /** 0..1 (1 = identical across known dims). 0.5 neutral when unknown. */
        public final double similarity;
        /** 0..1 fraction of dimensions actually compared. */
        public final double confidence;

        Result(double similarity, double confidence) {
            this.similarity = similarity;
            this.confidence = confidence;
        }
    }

    public static Result compare(TrackFeatures a, TrackFeatures b) {
        if (a == null || b == null) return new Result(0.5, 0);
        double sum = 0;
        int n = 0;

        Double d;
        d = dist01(a.energy, b.energy);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.danceability, b.danceability);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.valence, b.valence);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.acousticness, b.acousticness);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.instrumentalness, b.instrumentalness);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.speechiness, b.speechiness);
        if (d != null) {
            sum += d;
            n++;
        }
        d = dist01(a.liveness, b.liveness);
        if (d != null) {
            sum += d;
            n++;
        }
        if (a.tempo != null && b.tempo != null) {
            sum += Math.min(1.0, Math.abs(a.tempo - b.tempo) / 250.0);
            n++;
        }
        if (a.loudness != null && b.loudness != null) {
            double na = (a.loudness + 60.0) / 60.0;
            double nb = (b.loudness + 60.0) / 60.0;
            sum += Math.min(1.0, Math.abs(na - nb));
            n++;
        }
        if (a.key != null && b.key != null && a.key >= 0 && b.key >= 0) {
            int diff = Math.abs(a.key - b.key) % 12;
            sum += Math.min(diff, 12 - diff) / 6.0;
            n++;
        }
        if (n == 0) return new Result(0.5, 0);
        double avgDistance = sum / n;
        // Confidence scales with coverage: 10/10 dims → 1.0.
        double confidence = Math.min(1.0, n / 10.0);
        return new Result(1.0 - avgDistance, confidence);
    }

    private static Double dist01(Double a, Double b) {
        if (a == null || b == null) return null;
        return Math.min(1.0, Math.abs(a - b));
    }
}

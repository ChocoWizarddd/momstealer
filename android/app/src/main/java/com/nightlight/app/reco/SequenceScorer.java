package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;

import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whole-sequence scoring (§ARCH): when several candidate sequences are
 * generated, the complete sequence is scored for musical coherence,
 * freshness, artist/album diversity, user affinity and recent-play penalty —
 * then the best sequence wins. A coherent 15-track arc beats 15 locally
 * optimal but globally scattered picks.
 *
 * score = 0.40 · coherence + 0.20 · freshness + 0.15 · artistDiv
 *       + 0.10 · albumDiv + 0.15 · affinity − 0.50 · recentHits
 */
public final class SequenceScorer {

    static final double W_COHERENCE = 0.40;
    static final double W_FRESHNESS = 0.20;
    static final double W_ARTIST_DIV = 0.15;
    static final double W_ALBUM_DIV = 0.10;
    static final double W_AFFINITY = 0.15;
    static final double W_RECENT_HIT = 0.50;

    public static final class SequenceScore {
        public double coherence;
        public double freshness;
        public double artistDiv;
        public double albumDiv;
        public double affinity;
        public int recentHits;
        public double total;
    }

    public static SequenceScore score(List<Track> picks,
                                      java.util.Map<String, Double> relatedness,
                                      java.util.Map<String, Double> taste,
                                      java.util.Map<String, Double> freshness,
                                      SessionProfile session) {
        return scoreWithTransitions(picks, relatedness, taste, freshness, session);
    }

    /**
     * Transition-aware variant: coherence is the mean PREV-anchored transition
     * score across the sequence (C1 vs seed, C2 vs C1, …), so sequences with
     * smooth handoffs win over sequences with one great opener and dead air.
     */
    public static SequenceScore scoreWithTransitions(List<Track> picks,
                                      java.util.Map<String, Double> transition,
                                      java.util.Map<String, Double> taste,
                                      java.util.Map<String, Double> freshness,
                                      SessionProfile session) {
        SequenceScore s = new SequenceScore();
        if (picks == null || picks.isEmpty()) return s;
        double relSum = 0, tasteSum = 0, freshSum = 0;
        Set<String> artists = new HashSet<>();
        Set<String> albums = new HashSet<>();
        int recent = 0;
        for (Track t : picks) {
            relSum += transition.getOrDefault(t.id, 0.0);
            tasteSum += taste.getOrDefault(t.id, 0.0);
            freshSum += freshness.getOrDefault(t.id, 0.0);
            String a = TitleGuard.norm(t.artists);
            if (!a.isEmpty()) artists.add(a);
            String al = TitleGuard.norm(t.album);
            if (!al.isEmpty()) albums.add(al);
            if (session != null) {
                for (Track r : session.lastTracks(5)) {
                    if (t.id != null && t.id.equals(r.id)) {
                        recent++;
                        break;
                    }
                }
            }
        }
        int n = picks.size();
        s.coherence = relSum / n;
        // Freshness/affinity parts are small-magnitude sums; normalize by
        // their observed scale so they actually move the total.
        s.freshness = Math.max(0.0, Math.min(1.0, freshSum / n / 0.20));
        s.artistDiv = (double) artists.size() / n;
        s.albumDiv = (double) albums.size() / n;
        s.affinity = Math.max(0.0, Math.min(1.0, tasteSum / n / 0.60));
        s.recentHits = recent;
        s.total = W_COHERENCE * s.coherence
                + W_FRESHNESS * s.freshness
                + W_ARTIST_DIV * s.artistDiv
                + W_ALBUM_DIV * s.albumDiv
                + W_AFFINITY * s.affinity
                - W_RECENT_HIT * recent;
        return s;
    }

    public static String format(double v) {
        return String.format(Locale.US, "%.3f", v);
    }
}

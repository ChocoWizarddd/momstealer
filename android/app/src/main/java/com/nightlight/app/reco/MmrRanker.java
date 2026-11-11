package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sequential MMR re-ranking (§13, Carbonell & Goldstein / OpenSearch form):
 *
 *   MMR = λ · relevance − (1 − λ) · maxSimilarityToSelected
 *
 * plus explicit hard penalties (same artist/album/version-family caps,
 * recently-played suppression). The first pick is pure relevance; every
 * later pick balances relevance against redundancy with the already-built
 * sequence — this is what prevents Artist-A × 8 even when A tops relevance.
 */
public final class MmrRanker {

    private MmrRanker() {
    }

    public static final class Ranked {
        public final Candidate candidate;
        public final double relevance;
        public final double mmr;
        public final String reason;

        Ranked(Candidate candidate, double relevance, double mmr, String reason) {
            this.candidate = candidate;
            this.relevance = relevance;
            this.mmr = mmr;
            this.reason = reason;
        }
    }

    /**
     * Sequentially selects up to {@code k} candidates. {@code contextTrail}
     * seeds the "already selected" set so batches continue a sequence (§14):
     * pass seed + previously chosen tracks.
     */
    public static List<Ranked> rank(List<Candidate> pool, List<Track> contextTrail,
                                    Map<String, Double> relevance, double lambda, int k,
                                    SessionProfile session) {
        List<Ranked> out = new ArrayList<>();
        if (pool == null || pool.isEmpty() || k <= 0) return out;
        List<Track> selected = new ArrayList<>();
        if (contextTrail != null) selected.addAll(contextTrail);
        Set<String> selectedIds = new HashSet<>();
        for (Track t : selected) if (t != null && t.id != null) selectedIds.add(t.id);

        List<Candidate> remaining = new ArrayList<>(pool);
        Map<String, Integer> artistRuns = new HashMap<>();
        while (out.size() < k && !remaining.isEmpty()) {
            Candidate best = null;
            double bestMmr = Double.NEGATIVE_INFINITY;
            double bestRel = 0;
            String bestReason = "";
            for (Candidate c : remaining) {
                if (c == null || c.track == null || c.track.id == null) continue;
                if (selectedIds.contains(c.track.id)) continue;
                double rel = relevance.getOrDefault(c.track.id, 0.0);
                double sim = maxSimilarity(c.track, selected);
                double mmr = lambda * rel - (1 - lambda) * sim;
                // Hard diversity penalties (explicit caps, §13).
                String penaltyReason = "";
                if (recentlyPlayed(c.track, session)) {
                    mmr -= 0.60;
                    penaltyReason = "recently-played −0.60";
                }
                // Soft artist saturation (§14–15): first occurrence neutral,
                // second acceptable, third penalized, long runs penalized
                // hard. Consecutive runs — not window counts — so a returning
                // artist after other music is never punished for history.
                // Coherence first: these never outweigh a large relevance gap
                // on their own (MMR picks max; penalties only break ties).
                int run = trailingArtistRun(selected, norm(c.track.artists));
                if (run >= 3) {
                    mmr -= 0.60;
                    penaltyReason = "artist-run-" + (run + 1) + " −0.60";
                } else if (run == 2) {
                    mmr -= 0.25;
                    penaltyReason = "artist-run-3 −0.25";
                } else if (run == 1 && sameAlbumAsSelected(c.track, selected)) {
                    mmr -= 0.20;
                    penaltyReason = "album-cap −0.20";
                } else if (run == 1) {
                    mmr -= 0.05;
                    penaltyReason = "artist-pair −0.05";
                }
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = c;
                    bestRel = rel;
                    bestReason = describe(c, sim, penaltyReason);
                }
            }
            if (best == null) break;
            remaining.remove(best);
            out.add(new Ranked(best, bestRel, bestMmr, bestReason));
            selected.add(best.track);
            selectedIds.add(best.track.id);
            artistRuns.put(norm(best.track.artists),
                    artistRuns.getOrDefault(norm(best.track.artists), 0) + 1);
        }
        return out;
    }

    /** Inter-candidate similarity 0..1 (same scale as relevance deltas). */
    public static double similarity(Track a, Track b) {
        if (a == null || b == null) return 0;
        if (a.id != null && a.id.equals(b.id)) return 1.0;
        if (TitleGuard.isSameTitle(a, b)) return 0.95;
        double sim = 0;
        String aa = norm(a.artists);
        String ab = norm(b.artists);
        if (!aa.isEmpty() && aa.equals(ab)) sim = Math.max(sim, 0.60);
        String ala = norm(a.album);
        String alb = norm(b.album);
        if (!ala.isEmpty() && ala.equals(alb)) sim = Math.max(sim, 0.40);
        String ga = GenreHelper.genreOf(a);
        String gb = GenreHelper.genreOf(b);
        if (!ga.isEmpty() && ga.equals(gb)) sim = Math.max(sim, 0.20);
        else if (GenreHelper.isRelated(ga, gb)) sim = Math.max(sim, 0.10);
        return Math.min(1.0, sim);
    }

    private static double maxSimilarity(Track t, List<Track> selected) {
        double max = 0;
        // Only the recent window matters for redundancy (bounded, sequential).
        int from = Math.max(0, selected.size() - 8);
        for (int i = from; i < selected.size(); i++) {
            max = Math.max(max, similarity(t, selected.get(i)));
        }
        return max;
    }

    private static boolean recentlyPlayed(Track t, SessionProfile session) {
        if (session == null || t.id == null) return false;
        List<Track> last = session.lastTracks(5);
        for (Track r : last) {
            if (t.id.equals(r.id)) return true;
        }
        return false;
    }

    private static int trailingArtistRun(List<Track> selected, String artist) {
        if (artist.isEmpty() || selected.isEmpty()) return 0;
        int run = 0;
        for (int i = selected.size() - 1; i >= 0; i--) {
            if (artist.equals(norm(selected.get(i).artists))) run++;
            else break;
        }
        return run;
    }

    private static boolean sameAlbumAsSelected(Track t, List<Track> selected) {
        String al = norm(t.album);
        if (al.isEmpty()) return false;
        int from = Math.max(0, selected.size() - 6);
        for (int i = from; i < selected.size(); i++) {
            if (al.equals(norm(selected.get(i).album))) return true;
        }
        return false;
    }

    private static String describe(Candidate c, double sim, String penalty) {
        StringBuilder sb = new StringBuilder();
        sb.append("rel");
        boolean first = true;
        List<String> srcs = new ArrayList<>();
        for (Candidate.Source s : c.sources) srcs.add(s.name().toLowerCase(java.util.Locale.ROOT));
        java.util.Collections.sort(srcs);
        sb.append(" src=").append(srcs);
        sb.append(" maxSim=").append(String.format(java.util.Locale.US, "%.2f", sim));
        if (!penalty.isEmpty()) sb.append(' ').append(penalty);
        if (c.reason != null && !c.reason.isEmpty()) sb.append(" (").append(c.reason).append(')');
        return sb.toString();
    }

    private static String norm(String s) {
        return TitleGuard.norm(s == null ? "" : s);
    }
}

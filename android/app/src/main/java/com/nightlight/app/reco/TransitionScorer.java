package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transition-first scoring (§2, PDF §4): HOW WELL DOES C FOLLOW THE PREVIOUS
 * TRACK? FinalScore(candidate) =
 *     transition·0.40 + sessionFit·0.25 + taste·0.10 + freshness·0.10
 *     + discovery·0.05 + diversity·0.10 − penalties.
 *
 * The immediate transition carries the highest influence. Audio features
 * (tempo/energy/danceability/valence/acousticness) contribute 0 today —
 * unavailable in current metadata — with neutral handling (never zeroed
 * punitively); see TrackFeatures hook point in the backend contract.
 */
public final class TransitionScorer {

    public static final double W_TRANSITION = 0.40;
    public static final double W_SESSION = 0.25;
    public static final double W_TASTE = 0.10;
    public static final double W_FRESHNESS = 0.10;
    public static final double W_DISCOVERY = 0.05;
    public static final double W_DIVERSITY = 0.10;

    // Transition sub-weights (sum 1.0).
    static final double T_NEIGHBORHOOD = 0.45;
    static final double T_ARTIST = 0.20;
    static final double T_LANGUAGE = 0.20;
    static final double T_MOOD = 0.15;

    // Penalties (subtracted).
    public static final double P_LANGUAGE_MISMATCH = 0.50;
    public static final double P_LANGUAGE_MIXED = 0.15;
    public static final double P_ARTIST_RUN = 0.60;
    public static final double P_ARTIST_PAIR = 0.10;
    public static final double P_ALBUM_REPEAT = 0.25;
    public static final double P_RECENTLY_PLAYED = 0.80;
    public static final double P_SKIPPED_ARTIST = 0.40;

    public static final class In {
        public Track prev;
        public SessionContext ctx;
        public Candidate cand;
        /** Normalized 0..1 user affinity (from taste breakdown). */
        public double taste;
        /** Normalized 0..1 freshness (fewer-repeats AFTER relevance). */
        public double freshness;
        /** Normalized 0..1 diversity value (1 − max similarity to selected). */
        public double diversity;
        public boolean exploration;
    }

    public static final class Breakdown {
        public final Map<String, Double> parts = new LinkedHashMap<>();
        public double transition;
        public double sessionFit;
        public double taste;
        public double freshness;
        public double discovery;
        public double diversity;
        public double penalty;
        public double total;
        public String reason = "";

        void add(String dim, double value) {
            parts.put(dim, value);
        }
    }

    public Breakdown score(In in) {
        Breakdown b = new Breakdown();
        if (in == null || in.cand == null || in.cand.track == null) return b;
        Track c = in.cand.track;
        Track prev = in.prev;
        SessionContext ctx = in.ctx;

        // --- transition: C after PREV ---
        double neighborhood = 0.5; // neutral when no previous (sequence start)
        double artist = 0;
        double language = 0.5;
        double mood = 0;
        if (prev != null) {
            int dist = MusicNeighborhood.distance(
                    GenreHelper.genreOf(prev), GenreHelper.genreOf(c));
            neighborhood = dist == 0 ? 1.0 : dist == 1 ? 0.75 : dist == 2 ? 0.45 : 0.15;
            String pa = TitleGuard.norm(prev.artists);
            String ca = TitleGuard.norm(c.artists);
            if (!pa.isEmpty() && pa.equals(ca)) artist = 1.0;
            else if (RelatednessScorer.sharePrimaryArtist(pa, ca)) artist = 0.6;
            language = languageCompat(prev, c, ctx);
            String pm = moodOf(prev);
            String cm = moodOf(c);
            if (pm != null && pm.equals(cm)) mood = 1.0;
        }
        b.transition = T_NEIGHBORHOOD * neighborhood + T_ARTIST * artist
                + T_LANGUAGE * language + T_MOOD * mood;
        b.add("transition", b.transition);

        // --- session fit: C against the evolving vibe ---
        b.sessionFit = sessionFit(ctx, c, prev);
        b.add("session", b.sessionFit);

        b.taste = clamp01(in.taste);
        b.add("taste", b.taste);
        b.freshness = clamp01(in.freshness);
        b.add("freshness", b.freshness);
        b.discovery = in.exploration ? 1.0 : 0.0;
        b.add("discovery", b.discovery);
        b.diversity = clamp01(in.diversity);
        b.add("diversity", b.diversity);

        // --- penalties ---
        double pen = 0;
        StringBuilder why = new StringBuilder();
        if (ctx != null) {
            // Language: outside the session top-2 languages → strong penalty.
            // A mixing session does NOT excuse a third, unheard language:
            // only languages the session demonstrably plays travel free.
            String clang = lang(c);
            List<String> top = ctx.topLanguages(2);
            if (!clang.isEmpty() && !top.contains(clang) && ctx.size() >= 2) {
                pen += P_LANGUAGE_MISMATCH;
                why.append("LANGUAGE_MISMATCH ");
            }
            int run = ctx.artistRunLength(TitleGuard.norm(c.artists));
            if (run >= 2) {
                pen += P_ARTIST_RUN;
                why.append("ARTIST_SATURATED ");
            } else if (run == 1 && prev != null
                    && TitleGuard.norm(prev.artists).equals(TitleGuard.norm(c.artists))) {
                pen += P_ARTIST_PAIR;
                why.append("artist-pair ");
            }
        }
        if (prev != null) {
            String pal = TitleGuard.norm(prev.album);
            String cal = TitleGuard.norm(c.album);
            if (!pal.isEmpty() && pal.equals(cal)) {
                pen += P_ALBUM_REPEAT;
                why.append("album-repeat ");
            }
        }
        b.penalty = pen;
        b.add("penalty", -pen);

        b.total = W_TRANSITION * b.transition
                + W_SESSION * b.sessionFit
                + W_TASTE * b.taste
                + W_FRESHNESS * b.freshness
                + W_DISCOVERY * b.discovery
                + W_DIVERSITY * b.diversity
                - pen;
        b.reason = humanReason(c, b, why.toString().trim());
        return b;
    }

    /** Session fit: neighborhood distance to the DOMINANT vibe genre + top-2 language. */
    static double sessionFit(SessionContext ctx, Track c, Track seedFallback) {
        if (ctx == null || ctx.size() == 0) {
            // No vibe yet: proxy with the seed (sequence start).
            if (seedFallback == null) return 0.5;
            int dist = MusicNeighborhood.distance(
                    GenreHelper.genreOf(seedFallback), GenreHelper.genreOf(c));
            double genreScore = dist == 0 ? 1.0 : dist == 1 ? 0.75 : dist == 2 ? 0.45 : 0.15;
            String sl = lang(seedFallback);
            String cl = lang(c);
            double langScore = cl.isEmpty() || sl.isEmpty() ? 0.5 : (sl.equals(cl) ? 1.0 : 0.0);
            return 0.5 * genreScore + 0.5 * langScore;
        }
        String domGenre = ctx.dominantGenre();
        int dist = MusicNeighborhood.distance(domGenre, GenreHelper.genreOf(c));
        double genreScore = dist == 0 ? 1.0 : dist == 1 ? 0.75 : dist == 2 ? 0.45 : 0.15;
        String cl = lang(c);
        double langScore;
        if (cl.isEmpty()) {
            langScore = 0.5;
        } else if (ctx.topLanguages(2).contains(cl)) {
            langScore = 1.0;
        } else if (ctx.isMixingLanguages()) {
            langScore = 0.5;
        } else {
            langScore = 0.0;
        }
        return 0.5 * genreScore + 0.5 * langScore;
    }

    /** Language compatibility prev→candidate: same 1.0, unknown 0.5, else 0.0. */
    static double languageCompat(Track prev, Track c, SessionContext ctx) {
        String pl = lang(prev);
        String cl = lang(c);
        if (cl.isEmpty() || pl.isEmpty()) return 0.5;
        if (pl.equals(cl)) return 1.0;
        // Different from prev but inside the session's languages: travel, not teleport.
        if (ctx != null && ctx.topLanguages(2).contains(cl)) return 0.5;
        return 0.0;
    }

    private static String humanReason(Track c, Breakdown b, String penalties) {
        String genre = GenreHelper.genreOf(c);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "trans=%.2f sess=%.2f", b.transition, b.sessionFit));
        if (!genre.isEmpty() && !genre.equals(GenreHelper.UNKNOWN)) sb.append(' ').append(genre);
        if (!penalties.isEmpty()) sb.append(" [").append(penalties).append(']');
        else sb.append(" coherent");
        return sb.toString();
    }

    static String moodOf(Track t) {
        return ListeningContext.detectMood(t.name + " " + t.artists + " " + t.album);
    }

    static String lang(Track t) {
        return t.language == null ? "" : t.language.trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

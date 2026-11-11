package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transparent relevance scorer (§12). Every dimension is a named constant and
 * the per-candidate {@link Breakdown} exposes each contribution, so ranking
 * decisions are auditable (and unit-testable per dimension).
 *
 * Popularity is deliberately LIGHT: log-scaled with a low cap — it can only
 * nudge close calls, never dominate relevance.
 */
public final class RelevanceScorer {

    // Dimension weights (v1 deterministic; behavior pinned by RelevanceTest).
    static final double W_SEED_GENRE = 0.30;
    static final double W_SEED_ARTIST = 0.16;
    static final double W_SEED_MOOD = 0.14;
    static final double W_SEED_LANGUAGE = 0.10;
    static final double W_SEED_ERA = 0.05;
    static final double W_SESSION_GENRE = 0.22;
    static final double W_USER_GENRE = 0.20;
    static final double W_ARTIST_AFFINITY = 0.18;
    static final double W_MOOD_COMPAT = 0.12;
    static final double W_LANGUAGE_COMPAT = 0.08;
    static final double W_LIKED_ARTIST = 0.12;
    static final double W_COMPLETION_BONUS = 0.10;
    static final double W_NOVELTY = 0.10;
    static final double W_FRESHNESS = 0.08;
    static final double W_MULTI_SOURCE = 0.06;
    static final double W_POPULARITY_CAP = 0.06;
    static final double W_SKIP_PENALTY = -0.50;
    static final double W_EXPLICIT_MOOD_MISMATCH = -0.25;

    public static final class Breakdown {
        public final Map<String, Double> parts = new LinkedHashMap<>();
        public double total = 0;

        void add(String dim, double value) {
            parts.put(dim, value);
            total += value;
        }
    }

    public static final class Context {
        public Track seed;
        /** Evolving context: seed + already-selected (sequential, §14). */
        public List<Track> contextTrail;
        public String explicitMood; // null when none
        public SessionProfile session;
        public TasteProfile taste;
        public DiscoveryMode mode = DiscoveryMode.BALANCED;
    }

    public Breakdown score(Candidate c, Context ctx) {
        Breakdown b = new Breakdown();
        if (c == null || c.track == null || ctx == null || ctx.seed == null) return b;
        Track t = c.track;
        Track seed = ctx.seed;
        SessionProfile session = ctx.session;
        TasteProfile taste = ctx.taste;

        String seedGenre = GenreHelper.genreOf(seed);
        String candGenre = GenreHelper.genreOf(t);

        // --- current-track similarity ---
        if (candGenre.equals(seedGenre)) b.add("seed_genre", W_SEED_GENRE);
        else if (GenreHelper.isRelated(candGenre, seedGenre)) b.add("seed_genre", W_SEED_GENRE * 0.5);

        if (!norm(seed.artists).isEmpty() && norm(seed.artists).equals(norm(t.artists))) {
            b.add("seed_artist", W_SEED_ARTIST);
        }

        String seedMood = ListeningContext.detectMood(seed.name + " " + seed.artists + " " + seed.album);
        String candMood = ListeningContext.detectMood(t.name + " " + t.artists + " " + t.album);
        if (seedMood != null && seedMood.equals(candMood)) b.add("seed_mood", W_SEED_MOOD);
        if (ctx.explicitMood != null) {
            if (ctx.explicitMood.equals(candMood)) b.add("explicit_mood", W_SEED_MOOD);
            else b.add("explicit_mood", W_EXPLICIT_MOOD_MISMATCH);
        }

        if (!lang(seed).isEmpty() && lang(seed).equals(lang(t))) b.add("seed_language", W_SEED_LANGUAGE);
        if (!era(seed).isEmpty() && era(seed).equals(era(t))) b.add("seed_era", W_SEED_ERA);

        // --- session similarity (recent behavior outweighs old, §8) ---
        if (session != null) {
            b.add("session_genre", W_SESSION_GENRE * session.genreAffinity(candGenre));
            b.add("session_mood", candMood == null ? 0 : W_MOOD_COMPAT * 0.5 * session.moodAffinity(candMood));
            if (session.hasPlayed(t.id)) b.add("freshness", -W_FRESHNESS * 2);
            else b.add("freshness", W_FRESHNESS);
            if (session.recentSkips().contains(norm(t.artists))) b.add("session_skip", W_SKIP_PENALTY * 0.5);
        }

        // --- long-term user taste (§6) ---
        if (taste != null) {
            b.add("user_genre", W_USER_GENRE * taste.genreAffinity(candGenre));
            b.add("artist_affinity", W_ARTIST_AFFINITY * taste.artistAffinity(t.artists));
            b.add("mood_compat", candMood == null ? 0 : W_MOOD_COMPAT * 0.5 * taste.moodAffinity(candMood));
            b.add("language_compat", W_LANGUAGE_COMPAT * taste.languageAffinity(t.language));
            if (taste.isLiked(t.id)) b.add("liked_artist", W_LIKED_ARTIST);
            if (taste.wasCompleted(t.id)) b.add("completion_bonus", W_COMPLETION_BONUS);
            else b.add("novelty", W_NOVELTY * ctx.mode.noveltyWeight());
            double skip = taste.skipMass(t.id);
            if (skip > 0) b.add("user_skip", W_SKIP_PENALTY * Math.min(1.0, skip));
        } else {
            b.add("novelty", W_NOVELTY * ctx.mode.noveltyWeight());
        }

        // --- discovery-mode shaping (§15): discovery broadens related-genre
        // and exploration sources; familiar favors liked/known.
        if (ctx.mode == DiscoveryMode.DISCOVERY) {
            if (GenreHelper.isRelated(candGenre, seedGenre) && !candGenre.equals(seedGenre)) {
                b.add("mode_discovery", 0.10);
            }
            if (c.sources.contains(Candidate.Source.EXPLORATION)) b.add("mode_discovery", 0.08);
        } else if (ctx.mode == DiscoveryMode.FAMILIAR) {
            if (c.sources.contains(Candidate.Source.LIKED_NEIGHBORHOOD)
                    || c.sources.contains(Candidate.Source.FAVORITE_ARTIST)
                    || c.sources.contains(Candidate.Source.RECENT_SUCCESS)) {
                b.add("mode_familiar", 0.12);
            }
        }

        // --- independent evidence + light popularity ---
        if (c.sources.size() >= 2) b.add("multi_source", W_MULTI_SOURCE * Math.min(3, c.sources.size() - 1));
        if (t.playCount > 0) {
            double log = Math.log10((double) t.playCount + 1.0);
            b.add("popularity", Math.min(W_POPULARITY_CAP, Math.max(0, (log - 4.0) * 0.02)));
        }
        return b;
    }

    private static String norm(String s) {
        return TitleGuard.norm(s == null ? "" : s);
    }

    private static String lang(Track t) {
        return t.language == null ? "" : t.language.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String era(Track t) {
        if (t.year == null) return "";
        String y = t.year.trim();
        if (y.length() < 4) return "";
        try {
            int year = Integer.parseInt(y.substring(0, 4));
            return (year / 10) * 10 + "s";
        } catch (Exception e) {
            return "";
        }
    }
}

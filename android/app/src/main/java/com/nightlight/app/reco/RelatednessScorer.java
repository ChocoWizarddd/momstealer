package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.ListeningContext;
import com.nightlight.app.smartshuffle.TitleGuard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit musical relatedness between a SEED track and a candidate (§ARCH).
 * Seed-anchored, metadata-only, popularity-free: a famous unrelated song
 * scores ~0.2 here no matter its play count.
 *
 * Dimensions (weights sum > 1; scores are NOT probabilities):
 * - same genre ................. 0.35  (passes the floor alone)
 * - related genre .............. 0.18
 * - same artist ................ 0.20
 * - same language .............. 0.10
 * - same mood/vibe ............. 0.10
 * - provider radio member ...... 0.12  (similar-track relationship)
 * - same album ................. 0.08
 * Tempo/key: unavailable in current metadata (weight 0, documented).
 *
 * Relevance floor (§ARCH): candidates below the floor are rejected when
 * enough valid related candidates exist. Fallback ladder relaxes in order:
 * L0 0.30 (strict) → L1 0.20 (mood relaxed) → L2 0.10 (genre relaxed) →
 * L3 0.00 (exploration allowed). Never jumps straight to arbitrary tracks.
 */
public final class RelatednessScorer {

    public static final double W_GENRE = 0.35;
    public static final double W_RELATED_GENRE = 0.18;
    public static final double W_ARTIST = 0.20;
    public static final double W_LANGUAGE = 0.10;
    public static final double W_MOOD = 0.10;
    public static final double W_RADIO = 0.12;
    public static final double W_ALBUM = 0.08;

    /** Floor ladder: strict → mood-relaxed → genre-relaxed → open. */
    public static final double[] FLOOR_LADDER = {0.30, 0.20, 0.10, 0.0};
    /** Minimum floor-passers to accept a level instead of relaxing further. */
    public static final int MIN_PASSERS = 10;
    /** Freshness bonus for the anchored first pick (related + fresh). */
    public static final double FIRST_PICK_FRESHNESS_BONUS = 0.15;

    public static final class Breakdown {
        public final Map<String, Double> parts = new LinkedHashMap<>();
        public double total = 0;

        void add(String dim, double value) {
            parts.put(dim, value);
            total += value;
        }
    }

    public Breakdown score(Track seed, Candidate candidate) {
        Breakdown b = new Breakdown();
        if (seed == null || candidate == null || candidate.track == null) return b;
        Track t = candidate.track;

        String seedGenre = GenreHelper.genreOf(seed);
        String candGenre = GenreHelper.genreOf(t);
        if (!candGenre.isEmpty() && candGenre.equals(seedGenre)) {
            b.add("genre", W_GENRE);
        } else if (GenreHelper.isRelated(candGenre, seedGenre)) {
            b.add("related_genre", W_RELATED_GENRE);
        }

        String seedArtists = TitleGuard.norm(seed.artists);
        String candArtists = TitleGuard.norm(t.artists);
        if (!seedArtists.isEmpty() && !candArtists.isEmpty()) {
            if (seedArtists.equals(candArtists)) {
                b.add("artist", W_ARTIST);
            } else if (sharePrimaryArtist(seedArtists, candArtists)) {
                b.add("artist", W_ARTIST * 0.5);
            }
        }

        String seedLang = lang(seed);
        String candLang = lang(t);
        if (!seedLang.isEmpty() && seedLang.equals(candLang)) {
            b.add("language", W_LANGUAGE);
        }

        String seedMood = ListeningContext.detectMood(
                seed.name + " " + seed.artists + " " + seed.album);
        String candMood = ListeningContext.detectMood(
                t.name + " " + t.artists + " " + t.album);
        if (seedMood != null && seedMood.equals(candMood)) {
            b.add("mood", W_MOOD);
        }

        if (candidate.sources.contains(Candidate.Source.RADIO_SEED)) {
            b.add("radio", W_RADIO);
        }

        String seedAlbum = TitleGuard.norm(seed.album);
        String candAlbum = TitleGuard.norm(t.album);
        if (!seedAlbum.isEmpty() && seedAlbum.equals(candAlbum)) {
            b.add("album", W_ALBUM);
        }
        return b;
    }

    /** True when the two artist strings share a primary (first) artist. */
    static boolean sharePrimaryArtist(String a, String b) {
        String pa = primary(a);
        String pb = primary(b);
        return !pa.isEmpty() && pa.equals(pb);
    }

    private static String primary(String artists) {
        int comma = artists.indexOf(',');
        String p = (comma >= 0 ? artists.substring(0, comma) : artists).trim();
        // "X feat Y" / "X & Y": primary is the part before the joiner.
        p = p.replaceAll("\\s+(feat\\.?|ft\\.?|featuring|&|and|with|x)\\s+.*$", "").trim();
        return p;
    }

    private static String lang(Track t) {
        return t.language == null ? "" : t.language.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Genre inference and normalization for tracks that carry no explicit genre field.
 * Uses language, album/artist keywords, and a curated family map.
 * Pure Java — unit testable.
 */
public final class GenreHelper {

    private GenreHelper() {}

    // Canonical families
    public static final String POP = "pop";
    public static final String ROCK = "rock";
    public static final String PUNJABI = "punjabi";
    public static final String BOLLYWOOD = "bollywood";
    public static final String CHILL = "chill";
    public static final String HIPHOP = "hiphop";
    public static final String RNB = "rnb";
    public static final String ELECTRONIC = "electronic";
    public static final String INDIE = "indie";
    public static final String ACOUSTIC = "acoustic";
    public static final String KPOP = "kpop";
    public static final String METAL = "metal";
    public static final String UNKNOWN = "unknown";

    private static final Map<String, String> TOKEN_TO_FAMILY;
    private static final Map<String, Set<String>> RELATED;

    static {
        Map<String, String> m = new HashMap<>();
        // pop variants -> pop
        for (String s : Arrays.asList("pop", "pop music", "uk pop", "indian pop", "dance pop", "electropop")) m.put(s, POP);
        m.put("indie pop", POP); m.put("soft pop", POP); m.put("acoustic pop", POP); m.put("r&b-pop", POP); m.put("r&b pop", POP);
        m.put("r&b", RNB); m.put("rnb", RNB); m.put("soul", RNB);
        // rock
        for (String s : Arrays.asList("rock", "alternative rock", "pop rock", "indie rock", "soft rock", "hard rock")) m.put(s, ROCK);
        // punjabi
        for (String s : Arrays.asList("punjabi", "punjabi pop", "bhangra", "punjabi bhangra")) m.put(s, PUNJABI);
        // bollywood / hindi
        for (String s : Arrays.asList("bollywood", "hindi", "hindi pop", "filmi")) m.put(s, BOLLYWOOD);
        // chill
        for (String s : Arrays.asList("chill", "lo-fi", "lofi", "ambient", "downtempo", "easy listening")) m.put(s, CHILL);
        m.put("acoustic", ACOUSTIC);
        // hiphop
        for (String s : Arrays.asList("hip hop", "hiphop", "hip-hop", "rap", "trap")) m.put(s, HIPHOP);
        for (String s : Arrays.asList("electronic", "edm", "house", "techno", "dance", "workout edm")) m.put(s, ELECTRONIC);
        // Heavy music is its own neighborhood (NOT electropop-adjacent).
        for (String s : Arrays.asList("heavy metal", "metal", "death metal", "black metal", "thrash")) m.put(s, METAL);
        m.put("indie", INDIE);
        // K-pop lives next to global pop (see MusicNeighborhood).
        for (String s : Arrays.asList("k-pop", "kpop", "korean pop", "korean")) m.put(s, KPOP);
        TOKEN_TO_FAMILY = Collections.unmodifiableMap(m);

        Map<String, Set<String>> rel = new HashMap<>();
        rel.put(POP, new HashSet<>(Arrays.asList(INDIE, ACOUSTIC, RNB, CHILL, KPOP)));
        rel.put(PUNJABI, new HashSet<>(Arrays.asList(BOLLYWOOD, POP)));
        rel.put(CHILL, new HashSet<>(Arrays.asList(ACOUSTIC, POP, "ambient", INDIE)));
        rel.put(ROCK, new HashSet<>(Arrays.asList(INDIE, POP, ACOUSTIC, METAL)));
        rel.put(BOLLYWOOD, new HashSet<>(Arrays.asList(PUNJABI, POP, CHILL)));
        rel.put(HIPHOP, new HashSet<>(Arrays.asList(RNB, ELECTRONIC, POP)));
        rel.put(RNB, new HashSet<>(Arrays.asList(POP, HIPHOP, CHILL)));
        rel.put(ACOUSTIC, new HashSet<>(Arrays.asList(POP, CHILL, INDIE)));
        rel.put(INDIE, new HashSet<>(Arrays.asList(POP, ROCK, ACOUSTIC, CHILL)));
        rel.put(ELECTRONIC, new HashSet<>(Arrays.asList(HIPHOP, POP, CHILL)));
        rel.put(METAL, new HashSet<>(Arrays.asList(ROCK)));
        rel.put(KPOP, new HashSet<>(Arrays.asList(POP, RNB, HIPHOP, ELECTRONIC)));
        rel.put(UNKNOWN, new HashSet<>());
        RELATED = Collections.unmodifiableMap(rel);
    }

    public static String normalize(String raw) {
        if (raw == null) return UNKNOWN;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return UNKNOWN;
        if (TOKEN_TO_FAMILY.containsKey(s)) return TOKEN_TO_FAMILY.get(s);
        // substring fallback: if raw contains known token, map
        for (Map.Entry<String, String> e : TOKEN_TO_FAMILY.entrySet()) {
            if (s.contains(e.getKey())) return e.getValue();
        }
        return UNKNOWN;
    }

    /** Infer genre family for a track using language + album/artist keywords. */
    public static String genreOf(Track track) {
        if (track == null) return UNKNOWN;
        // 0. Trusted provider metadata first (§11 architecture: provider
        // fields are separate from heuristics and UNKNOWN stays UNKNOWN).
        if (track.genre != null && !track.genre.trim().isEmpty()) {
            String provider = normalize(track.genre);
            if (!UNKNOWN.equals(provider)) return provider;
        }
        // 1. album keyword scan (strongest proxy for genre)
        String album = track.album != null ? track.album.toLowerCase(Locale.ROOT) : "";
        if (!album.isEmpty()) {
            for (Map.Entry<String, String> e : TOKEN_TO_FAMILY.entrySet()) {
                if (album.contains(e.getKey()) && e.getKey().length() >= 3) return e.getValue();
            }
        }
        // 2. language not stored on Track — infer via album/artist contains region keywords
        String combinedLower = (track.artists + " " + album).toLowerCase(Locale.ROOT);
        if (combinedLower.contains("punjabi") || combinedLower.contains("bhangra")) return PUNJABI;
        if (combinedLower.contains("bollywood") || combinedLower.contains("hindi")) return BOLLYWOOD;
        // 3. artist keyword fallback
        String artist = track.artists != null ? track.artists.toLowerCase(Locale.ROOT) : "";
        for (Map.Entry<String, String> e : TOKEN_TO_FAMILY.entrySet()) {
            if (artist.contains(e.getKey()) && e.getKey().length() >= 4) return e.getValue();
        }
        return UNKNOWN;
    }

    public static boolean isRelated(String candidateFamily, String seedFamily) {
        if (candidateFamily.equals(seedFamily)) return false;
        Set<String> rel = RELATED.get(seedFamily);
        return rel != null && rel.contains(candidateFamily);
    }

    public static boolean isSameOrRelated(String candidate, String seed) {
        return candidate.equals(seed) || isRelated(candidate, seed);
    }

    public enum Tier { SAME, RELATED, DISCOVERY }

    public enum Source { PROVIDER, ALBUM_HEURISTIC, ARTIST_HEURISTIC, FALLBACK, UNKNOWN }
    public static final class GenreInfo {
        public final String family;
        public final double confidence;
        public final Source source;
        public GenreInfo(String family, double confidence, Source source) { this.family = family; this.confidence = confidence; this.source = source; }
    }
    public static GenreInfo genreInfoOf(Track track) {
        if (track == null) return new GenreInfo(UNKNOWN, 0.0, Source.UNKNOWN);
        // 1. trusted track genre metadata if provider supplies it
        if (track.genre != null && !track.genre.trim().isEmpty()) {
            String g = normalize(track.genre);
            if (!g.equals(UNKNOWN)) return new GenreInfo(g, 0.95, Source.PROVIDER);
        }
        // 2. language is NOT genre — keep separate, do not auto-map
        // 3. album heuristic (strongest proxy)
        String album = track.album != null ? track.album.toLowerCase(Locale.ROOT) : "";
        if (!album.isEmpty()) {
            for (Map.Entry<String, String> e : TOKEN_TO_FAMILY.entrySet()) if (album.contains(e.getKey()) && e.getKey().length() >= 3) return new GenreInfo(e.getValue(), 0.65, Source.ALBUM_HEURISTIC);
        }
        String combined = (track.artists + " " + album).toLowerCase(Locale.ROOT);
        if (combined.contains("punjabi") || combined.contains("bhangra")) return new GenreInfo(PUNJABI, 0.60, Source.ARTIST_HEURISTIC);
        if (combined.contains("bollywood") || combined.contains("hindi")) return new GenreInfo(BOLLYWOOD, 0.60, Source.ARTIST_HEURISTIC);
        String artist = track.artists != null ? track.artists.toLowerCase(Locale.ROOT) : "";
        for (Map.Entry<String, String> e : TOKEN_TO_FAMILY.entrySet()) if (artist.contains(e.getKey()) && e.getKey().length() >= 4) return new GenreInfo(e.getValue(), 0.55, Source.ARTIST_HEURISTIC);
        return new GenreInfo(UNKNOWN, 0.0, Source.FALLBACK);
    }

    public static Tier tier(String candidateFamily, String seedFamily) {
        if (candidateFamily.equals(seedFamily)) return Tier.SAME;
        if (isRelated(candidateFamily, seedFamily)) return Tier.RELATED;
        if (candidateFamily.equals(UNKNOWN) || seedFamily.equals(UNKNOWN)) return Tier.RELATED;
        return Tier.DISCOVERY;
    }
}

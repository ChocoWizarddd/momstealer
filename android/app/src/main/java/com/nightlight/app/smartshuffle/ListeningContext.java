package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The listening context for a session: a primary mood (when one is detectable),
 * the seed artist/album, and keyword evidence used by the scorer.
 *
 * Mood detection is deliberately conservative — keyword hits are weak signals,
 * never authoritative claims about a song's meaning.
 */
public final class ListeningContext {

    public static final String LOVE = "love";
    public static final String SAD = "sad";
    public static final String CHILL = "chill";
    public static final String HAPPY = "happy";
    public static final String ENERGY = "energy";
    public static final String WORKOUT = "workout";
    public static final String PARTY = "party";
    public static final String PUNJABI = "punjabi";
    public static final String BOLLYWOOD = "bollywood";
    public static final String ACOUSTIC = "acoustic";
    public static final String ROMANTIC = "romantic";
    public static final String LOFI = "lofi";
    public static final String FOCUS = "focus";

    /** Mood blends: a LOVE context also warms to romantic/emotional/acoustic. */
    public static final Map<String, Map<String, Double>> BLENDS = blends();

    /** Keyword evidence per mood (title/artist/album text, lowercased). */
    public static final Map<String, String[]> KEYWORDS = keywords();

    public final String primaryMood; // null when no evidence found
    public final double moodConfidence; // 0..1
    public final String seedArtist;
    public final String seedAlbum;

    ListeningContext(String primaryMood, double moodConfidence, Track seed) {
        this.primaryMood = primaryMood;
        this.moodConfidence = moodConfidence;
        this.seedArtist = seed != null ? seed.artists : "";
        this.seedAlbum = seed != null ? seed.album : "";
    }

    /** Infers a context from a seed track. Never throws; always usable. */
    public static ListeningContext fromTrack(Track seed) {
        if (seed == null) {
            return new ListeningContext(null, 0, null);
        }
        String text = norm(seed.name) + " " + norm(seed.artists) + " " + norm(seed.album);
        String best = null;
        double bestConf = 0;
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            double conf = evidence(e.getValue(), text);
            if (conf > bestConf) {
                bestConf = conf;
                best = e.getKey();
            }
        }
        bestConf = Math.min(bestConf, 0.5);
        return new ListeningContext(best, Math.max(0.08, bestConf), seed);
    }

    /**
     * Explicit user-selected mood: the strongest context signal. Confidence is
     * high and stable until the mood decays, unlike inferred evidence.
     */
    public static ListeningContext explicit(String mood, Track seed) {
        if (mood == null || !BLENDS.containsKey(mood)) {
            return fromTrack(seed);
        }
        return new ListeningContext(mood, 0.9, seed);
    }

    /** Returns the strongest mood key found in free text, or null. */
    public static String detectMood(String text) {
        String hay = norm(text);
        String best = null;
        double bestConf = 0;
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            double conf = evidence(e.getValue(), hay);
            if (conf > bestConf) {
                bestConf = conf;
                best = e.getKey();
            }
        }
        return bestConf > 0 ? best : null;
    }

    public Set<String> moodKeys() {
        return primaryMood == null ? Collections.emptySet() : Collections.singleton(primaryMood);
    }

    static double evidence(String[] words, String hay) {
        if (words == null || hay == null || hay.isEmpty()) {
            return 0;
        }
        double conf = 0;
        java.util.Set<String> tokens = new java.util.HashSet<>(
                java.util.Arrays.asList(hay.split(" ")));
        for (String kw : words) {
            if (kw == null || kw.isEmpty()) {
                continue;
            }
            String needle = kw.toLowerCase().trim();
            boolean hit;
            if (needle.contains(" ")) {
                // Preserve multi-word phrases such as "good time" and "ap dhillon".
                hit = hay.contains(needle);
            } else {
                // Whole-token matching prevents short/common words such as
                // "ho", "hai", "tum" from classifying unrelated songs.
                hit = tokens.contains(needle);
            }
            if (hit) {
                conf += needle.length() >= 5 ? 0.13 : 0.08;
            }
        }
        return conf;
    }

    static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase()
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("[-–—:;._+/,&]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Map<String, Map<String, Double>> blends() {
        Map<String, Map<String, Double>> b = new LinkedHashMap<>();
        b.put(LOVE, map(LOVE, 1.0, ROMANTIC, 0.85, ACOUSTIC, 0.5, BOLLYWOOD, 0.45, CHILL, 0.35, SAD, 0.2));
        b.put(SAD, map(SAD, 1.0, ACOUSTIC, 0.7, CHILL, 0.55, LOFI, 0.4, BOLLYWOOD, 0.3, LOVE, 0.35));
        b.put(CHILL, map(CHILL, 1.0, LOFI, 0.8, ACOUSTIC, 0.7, FOCUS, 0.5, SAD, 0.35, LOVE, 0.2));
        b.put(HAPPY, map(HAPPY, 1.0, PARTY, 0.7, ENERGY, 0.6, "pop", 0.5, "dance", 0.5, LOVE, 0.3));
        b.put(ENERGY, map(ENERGY, 1.0, PARTY, 0.8, WORKOUT, 0.85, "dance", 0.7, "pop", 0.5));
        b.put(WORKOUT, map(WORKOUT, 1.0, ENERGY, 0.9, PARTY, 0.5, "dance", 0.6, "pop", 0.4));
        b.put(PARTY, map(PARTY, 1.0, ENERGY, 0.7, PUNJABI, 0.55, "dance", 0.7, "pop", 0.5));
        b.put(PUNJABI, map(PUNJABI, 1.0, PARTY, 0.5, BOLLYWOOD, 0.35));
        b.put(BOLLYWOOD, map(BOLLYWOOD, 1.0, LOVE, 0.6, ROMANTIC, 0.6, SAD, 0.45, PUNJABI, 0.35));
        return b;
    }

    private static Map<String, String[]> keywords() {
        Map<String, String[]> k = new LinkedHashMap<>();
        k.put(LOVE, new String[]{"love", "romantic", "lover", "baby", "ishq", "mohabbat",
                "pyaar", "pyar", "jaan", "dil", "heart", "kiss", "forever", "husn", "tere", "tera", "tujhe"});
        k.put(SAD, new String[]{"sad", "cry", "crying", "alone", "broken", "heartbreak",
                "tears", "missing", "gone", "ranjha", "judai", "tanha"});
        k.put(CHILL, new String[]{"chill", "lofi", "lo-fi", "calm", "acoustic", "rain",
                "night", "slow", "soft", "relax", "sleep", "lullaby"});
        k.put(HAPPY, new String[]{"happy", "smile", "sunshine", "sunny", "good time",
                "celebration", "joy", "bright", "positive", "uplift"});
        k.put(ENERGY, new String[]{"energy", "fire", "burn", "strong", "power", "hype", "turbo"});
        k.put(WORKOUT, new String[]{"workout", "gym", "run", "running", "sweat",
                "pump", "beast", "training"});
        k.put(PARTY, new String[]{"party", "dance", "club", "bhangra", "maal", "celebrate", "gabru"});
        k.put(FOCUS, new String[]{"focus", "study", "work", "deep", "instrumental", "piano", "guitar"});
        k.put(PUNJABI, new String[]{"diljit", "sidhu", "ap dhillon", "karan aujla", "shubh",
                "amrit maan", "gurdas maan", "babbu maan", "punjab", "punjabi", "yaar"});
        k.put(BOLLYWOOD, new String[]{"bollywood", "hindi", "remake", "tum", "hum", "hai", "ho", "tere"});
        return k;
    }

    private static Map<String, Double> map(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), (Double) kv[i + 1]);
        }
        return m;
    }
}

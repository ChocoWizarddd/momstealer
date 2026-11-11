package com.nightlight.app.reco;

import com.nightlight.app.smartshuffle.GenreHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Musical neighborhoods: a configurable adjacency map over genre families so
 * the session can TRAVEL (K-pop → global pop → electropop → pop/R&B) instead
 * of teleporting (K-pop → Punjabi folk → metal).
 *
 * Distance: 0 = same family, 1 = adjacent neighborhood, 2 = two hops,
 * 3 = unrelated. There is no "infinity": unreachable pairs are still usable
 * at a high penalty so small pools never deadlock — the floor ladder and
 * penalties decide, not the map.
 *
 * This replaces scattering genre strings through scoring code. To retune
 * travel behavior, edit ONLY this map.
 */
public final class MusicNeighborhood {

    private MusicNeighborhood() {
    }

    private static final Map<String, Set<String>> NEIGHBORS;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        // K-pop lives next to global pop: the BTS → global-pop journey.
        m.put("kpop", set("pop", "rnb", "hiphop", "electronic"));
        m.put(GenreHelper.POP, set("kpop", "indie", "acoustic", "rnb", "chill", "electronic", "rock"));
        m.put(GenreHelper.RNB, set("pop", "hiphop", "chill", "kpop"));
        m.put(GenreHelper.HIPHOP, set("rnb", "pop", "electronic", "kpop"));
        m.put(GenreHelper.ELECTRONIC, set("pop", "hiphop", "chill", "kpop"));
        m.put(GenreHelper.ROCK, set("indie", "pop", "acoustic"));
        m.put(GenreHelper.INDIE, set("pop", "rock", "acoustic", "chill"));
        m.put(GenreHelper.ACOUSTIC, set("pop", "chill", "indie", "rock"));
        m.put(GenreHelper.CHILL, set("acoustic", "pop", "indie", "rnb"));
        m.put(GenreHelper.PUNJABI, set("bollywood", "pop", "hiphop"));
        m.put(GenreHelper.BOLLYWOOD, set("punjabi", "pop", "chill", "acoustic"));
        NEIGHBORS = Collections.unmodifiableMap(m);
    }

    private static Set<String> set(String... families) {
        return new HashSet<>(Arrays.asList(families));
    }

    /**
     * Neighborhood distance between two genre families (0..3). Unknown
     * families have no neighbors: same-unknown is 0 (shared context), any
     * known↔unknown pair is 2 (cautious, not damning — metadata gaps must
     * not teleport-block an otherwise related track).
     */
    public static int distance(String a, String b) {
        String fa = family(a);
        String fb = family(b);
        if (fa.equals(fb)) return 0;
        Set<String> na = NEIGHBORS.get(fa);
        if (na != null && na.contains(fb)) return 1;
        if (na != null) {
            for (String mid : na) {
                Set<String> nm = NEIGHBORS.get(mid);
                if (nm != null && nm.contains(fb)) return 2;
            }
        }
        return 3;
    }

    /** True for adjacent-or-same families (distance ≤ 1). */
    public static boolean adjacent(String a, String b) {
        return distance(a, b) <= 1;
    }

    private static String family(String genre) {
        if (genre == null || genre.isEmpty()) return GenreHelper.UNKNOWN;
        return genre;
    }
}

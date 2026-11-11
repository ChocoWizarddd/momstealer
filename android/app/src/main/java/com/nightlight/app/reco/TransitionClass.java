package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.TitleGuard;

/**
 * Transition diagnostics (§7–8): every emitted transition gets a class and
 * drift measurements so device logs answer "was this a believable next
 * song?" without trusting scores alone.
 *
 * Classes:
 * - DIRECT: same artist, or same neighborhood + same language.
 * - ADJACENT: neighboring musical neighborhoods (distance ≤ 1).
 * - BRIDGE: connects two different neighborhoods — adjacent to BOTH the
 *   previous track's neighborhood and the session-dominant one when they
 *   differ. Bridges are how sessions travel without teleporting.
 * - EXPLORATORY: passes the floor but thinly supported (< 2 evidence legs).
 * - TELEPORT: distant neighborhood (distance 3) AND language outside the
 *   session top-2. Unacceptable unless session history supports the jump.
 *
 * Target: DIRECT + ADJACENT + BRIDGE = healthy; EXPLORATORY limited;
 * TELEPORT ≈ 0 without session evidence.
 */
public final class TransitionClass {

    public enum Kind {
        DIRECT, ADJACENT, BRIDGE, EXPLORATORY, TELEPORT
    }

    private TransitionClass() {
    }

    public static Kind classify(Track prev, Track candidate, SessionContext ctx, int supportLegs) {
        if (prev == null || candidate == null) return Kind.EXPLORATORY;
        String pa = TitleGuard.norm(prev.artists);
        String ca = TitleGuard.norm(candidate.artists);
        String pg = GenreHelper.genreOf(prev);
        String cg = GenreHelper.genreOf(candidate);
        String pl = lang(prev);
        String cl = lang(candidate);

        int dist = MusicNeighborhood.distance(pg, cg);
        boolean sameArtist = !pa.isEmpty() && pa.equals(ca);
        boolean sameLang = !pl.isEmpty() && pl.equals(cl);

        // Teleport check first: distant + linguistically unsupported.
        boolean langSupported = cl.isEmpty() || pl.isEmpty() || sameLang
                || (ctx != null && ctx.topLanguages(2).contains(cl));
        if (dist >= 3 && !langSupported) return Kind.TELEPORT;

        if (sameArtist) return Kind.DIRECT;
        if (dist == 0 && sameLang) return Kind.DIRECT;
        if (dist <= 1) return Kind.ADJACENT;

        // Thinly supported jumps are exploratory even at distance 2.
        if (supportLegs < 2) return Kind.EXPLORATORY;

        // Bridge: spans prev and session-dominant neighborhoods.
        if (ctx != null) {
            String dom = ctx.dominantGenre();
            if (!dom.isEmpty() && !dom.equals(pg)) {
                int toDom = MusicNeighborhood.distance(cg, dom);
                int prevToDom = MusicNeighborhood.distance(pg, dom);
                if (toDom <= 1 && prevToDom >= 1) return Kind.BRIDGE;
            }
        }
        if (dist == 2) return Kind.BRIDGE;
        // Distant but linguistically supported: watch closely.
        return Kind.EXPLORATORY;
    }

    private static String lang(Track t) {
        return t.language == null ? "" : t.language.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

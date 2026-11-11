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
 * Hybrid candidate pool (§4–5). Slices provider/local lists into labeled
 * generator sources against the seed + profiles, then deduplicates by stable
 * track ID and version family (title normalization + primary artist — one
 * family member per session).
 *
 * Labeling is honest about what each slice IS:
 * - radio results ARE provider-related (A); the same-genre / related-genre /
 *   related-artist slices (B–D) are derived views over the same provider
 *   pool, not independent retrieval (embedding retrieval is the future hook).
 * - liked / favorite / recent-success (E–G) come from local library data.
 * - trending (H) from charts; exploration (I) from low-affinity slices.
 */
public final class CandidatePool {

    private CandidatePool() {
    }

    public static final class Input {
        public Track seed;
        public List<Track> radio = new ArrayList<>();
        public List<Track> liked = new ArrayList<>();
        public List<Track> recent = new ArrayList<>();
        public List<Track> trending = new ArrayList<>();
        /** Already-queued tracks carried across a toggle/augment (honest label). */
        public List<Track> carried = new ArrayList<>();
        /** Backend similarity candidates (external listening/audio signals). */
        public List<Track> similar = new ArrayList<>();
        public int explorationCap = 4;
    }

    /**
     * Builds the labeled, deduplicated pool. The seed itself and its version
     * family never enter (§5, §21-backend mirrors this for radio).
     */
    public static List<Candidate> build(Input in, SessionProfile session, TasteProfile taste) {
        List<Candidate> out = new ArrayList<>();
        if (in == null || in.seed == null) return out;
        String seedGenre = GenreHelper.genreOf(in.seed);
        String seedArtist = norm(in.seed.artists);
        String seedFamily = versionFamily(in.seed);
        Set<String> seenIds = new HashSet<>();
        seenIds.add(in.seed.id);
        Set<String> seenFamilies = new HashSet<>();
        seenFamilies.add(seedFamily);

        Map<String, Candidate> byId = new HashMap<>();

        // A: radio / related — every radio track starts here.
        for (Track t : safe(in.radio)) {
            Candidate c = add(byId, out, seenIds, seenFamilies, t,
                    Candidate.Source.RADIO_SEED, "related to " + shortName(in.seed));
            if (c == null) continue;
            String g = GenreHelper.genreOf(t);
            // B: related artist = provider-related AND different artist.
            if (!norm(t.artists).isEmpty() && !norm(t.artists).equals(seedArtist)) {
                c.addSource(Candidate.Source.RELATED_ARTIST, null);
            }
            // C/D: genre slices.
            if (g.equals(seedGenre)) {
                c.addSource(Candidate.Source.SAME_GENRE, null);
            } else if (GenreHelper.isRelated(g, seedGenre)) {
                c.addSource(Candidate.Source.RELATED_GENRE, null);
            }
        }

        // E: liked-track neighborhood — liked tracks themselves are playable
        // candidates (familiarity), labeled distinctly.
        for (Track t : safe(in.liked)) {
            Candidate c = add(byId, out, seenIds, seenFamilies, t,
                    Candidate.Source.LIKED_NEIGHBORHOOD, "from your likes");
            if (c != null && taste != null && taste.isLiked(t.id)) {
                c.addSource(Candidate.Source.FAVORITE_ARTIST, null);
            }
        }

        // F: favorite artists — radio/liked tracks by top-affinity artists.
        if (taste != null) {
            Set<String> favs = new HashSet<>(taste.topArtists(5));
            for (Candidate c : out) {
                if (favs.contains(norm(c.track.artists))) {
                    c.addSource(Candidate.Source.FAVORITE_ARTIST, null);
                }
            }
        }

        // G: recent-success neighborhood.
        if (session != null) {
            Set<String> successIds = new HashSet<>();
            for (Track t : session.recentSuccesses(10)) successIds.add(t.id);
            for (Candidate c : out) {
                if (successIds.contains(c.track.id)) {
                    c.addSource(Candidate.Source.RECENT_SUCCESS, null);
                }
            }
        }

        // H: trending — appended only if absent (light signal, §12).
        for (Track t : safe(in.trending)) {
            add(byId, out, seenIds, seenFamilies, t, Candidate.Source.TRENDING, "trending now");
        }

        // Carried queue tracks (toggle/augment path): kept as candidates so
        // the user's existing queue is re-ranked, not discarded. Labeled.
        for (Track t : safe(in.carried)) {
            add(byId, out, seenIds, seenFamilies, t, Candidate.Source.CARRIED_QUEUE, "already queued");
        }

        // External similarity (backend Last.fm/ReccoBeats re-resolved to
        // playable rows): independent listening/audio evidence.
        for (Track t : safe(in.similar)) {
            add(byId, out, seenIds, seenFamilies, t, Candidate.Source.EXTERNAL_SIMILAR, "externally similar");
        }

        // Family dedupe: keep the best-labeled member per version family.
        // (add() keeps first-seen; radio-first ordering prefers provider-
        // related originals; re-pick by source count.)
        Map<String, Candidate> familyBest = new HashMap<>();
        for (Candidate c : out) {
            String fam = versionFamily(c.track);
            Candidate cur = familyBest.get(fam);
            if (cur == null || c.sources.size() > cur.sources.size()) {
                familyBest.put(fam, c);
            }
        }
        List<Candidate> deduped = new ArrayList<>(familyBest.values());

        // I: exploration labels — low user-affinity, non-trending candidates
        // are valid controlled-discovery picks (still relevant: they came
        // from the related pool, never random injection).
        if (taste != null) {
            int labeled = 0;
            for (Candidate c : deduped) {
                if (labeled >= Math.max(0, in.explorationCap)) break;
                boolean known = taste.genreAffinity(GenreHelper.genreOf(c.track)) > 0.55
                        || taste.artistAffinity(c.track.artists) > 0.55
                        || c.sources.contains(Candidate.Source.LIKED_NEIGHBORHOOD)
                        || c.sources.contains(Candidate.Source.TRENDING);
                if (!known && c.sources.contains(Candidate.Source.RADIO_SEED)) {
                    c.addSource(Candidate.Source.EXPLORATION, null);
                    labeled++;
                }
            }
        }
        return deduped;
    }

    private static List<Track> safe(List<Track> l) {
        return l != null ? l : new ArrayList<Track>();
    }

    private static Candidate add(Map<String, Candidate> byId, List<Candidate> out,
                                 Set<String> seenIds, Set<String> excludedFamilies, Track t,
                                 Candidate.Source source, String reason) {
        if (t == null || t.id == null) return null;
        // Same stable id from another source: merge labels (independent
        // evidence), never duplicate.
        Candidate existing = byId.get(t.id);
        if (existing != null) {
            existing.addSource(source, reason);
            return existing;
        }
        seenIds.add(t.id);
        // The seed's version family never enters (same song, any version).
        // Other families merge labels across sources here and are reduced to
        // one member each by the family-best pass below.
        if (excludedFamilies != null && excludedFamilies.contains(versionFamily(t))) return null;
        Candidate c = new Candidate(t, source, reason);
        byId.put(t.id, c);
        out.add(c);
        return c;
    }

    static String versionFamily(Track t) {
        if (t == null || t.name == null) return "";
        String base = TitleGuard.canonicalTitle(t.name);
        String artist = norm(t.artists);
        int comma = artist.indexOf(',');
        String primary = (comma >= 0 ? artist.substring(0, comma) : artist).trim();
        return base + "::" + primary;
    }

    private static String shortName(Track t) {
        String n = t.name != null ? t.name : "?";
        return n.length() > 28 ? n.substring(0, 28) + "…" : n;
    }

    private static String norm(String s) {
        return TitleGuard.norm(s == null ? "" : s);
    }
}

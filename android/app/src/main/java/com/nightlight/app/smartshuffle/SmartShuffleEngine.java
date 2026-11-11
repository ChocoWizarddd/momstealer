package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Genre-aware curated queue. Scores NEXT candidate against current track + session
 * profile, enforces 70-80% same genre / 15-25% related / 0-10% discovery,
 * artist/album/title cooldowns, recent history, mood and language continuity.
 */
public final class SmartShuffleEngine {

    private static final double TEMPERATURE = 1.6;
    private static final int MAX_QUEUE = 20;

    private final Random random;

    public SmartShuffleEngine() { this(new Random()); }
    public SmartShuffleEngine(Random random) { this.random = random != null ? random : new Random(); }

    public List<Track> generateQueue(Track seed, List<Track> candidates, List<Track> recent) {
        return generateQueue(seed, candidates, recent, null, 0.18, Collections.emptySet(), Collections.emptySet(), null);
    }

    public List<Track> generateQueue(Track seed, List<Track> candidates, List<Track> recent,
                                      String explicitMood, double discoveryRatio,
                                      Set<String> skipArtists, Set<String> likedIds,
                                      String currentTrackId) {
        // If the pool was built from a stale or unrelated source (e.g. a
        // JioSaavn search list that was handed to the queue), fall back to
        // the old-radial behavior: keep the session alive and continue from
        // the related-song radio path instead of reshuffling unrelated tracks.
        // That is what kept playback flowing before the app restart.
        List<Track> pool = new ArrayList<>();
        if (candidates != null) {
            Set<String> seenIds = new HashSet<>();
            for (Track t : candidates) {
                if (t.id == null) continue;
                if (!seenIds.add(t.id)) continue;
                if (currentTrackId != null && t.id.equals(currentTrackId)) continue;
                pool.add(t);
            }
        }
        if (pool.isEmpty()) return pool;
        List<Track> recentSafe = recent != null ? recent : new ArrayList<>();
        ListeningContext ctx = explicitMood != null ? ListeningContext.explicit(explicitMood, seed) : ListeningContext.fromTrack(seed);
        double discovery = Math.max(0.0, Math.min(1.0, discoveryRatio));
        Set<String> skipped = new HashSet<>();
        if (skipArtists != null) for (String s : skipArtists) { String n = norm(s); if (!n.isEmpty()) skipped.add(n); }
        Set<String> liked = likedIds != null ? likedIds : Collections.emptySet();

        Map<String, Integer> recentTracks = new HashMap<>();
        Map<String, Integer> recentArtists = new HashMap<>();
        Map<String, Integer> recentAlbums = new HashMap<>();
        for (int i = Math.max(0, recentSafe.size() - 15); i < recentSafe.size(); i++) {
            Track t = recentSafe.get(i);
            if (t == null || t.id == null) continue;
            recentTracks.merge(t.id, 1, Integer::sum);
            recentArtists.merge(norm(t.artists), 1, Integer::sum);
            recentAlbums.merge(norm(t.album), 1, Integer::sum);
        }

        String seedGenre = GenreHelper.genreOf(seed);
        boolean isExplicitMood = explicitMood != null;

        // Adaptive artist cooldown: a singer never returns within 8 songs,
        // but only when the pool holds enough distinct artists to honor it
        // (window = distinct-1). Small pools degrade to run-blocking only.
        // Albums stay on the pre-existing run-block + soft penalties — a hard
        // album window starves mood continuity (few albums per mood).
        Set<String> distinctArtists = new HashSet<>();
        for (Track t : pool) {
            String a = norm(t.artists);
            if (!a.isEmpty()) distinctArtists.add(a);
        }
        int artistWindow = distinctArtists.size() <= 1 ? 0 : Math.min(ARTIST_COOLDOWN, distinctArtists.size() - 1);

        List<Track> out = new ArrayList<>();
        Map<String, Integer> inArtists = new HashMap<>();
        Map<String, Integer> inAlbums = new HashMap<>();
        Set<String> selectedVersionFamilies = new HashSet<>();
        Set<String> selectedIds = new HashSet<>();
        int sameCount = 0, relatedCount = 0, discoveryCount = 0;

        while (!pool.isEmpty() && out.size() < MAX_QUEUE) {
            String lastGenre = out.isEmpty() ? null : GenreHelper.genreOf(out.get(out.size() - 1));
            String lastArtist = out.isEmpty() ? null : norm(out.get(out.size() - 1).artists);
            String lastAlbum = out.isEmpty() ? null : norm(out.get(out.size() - 1).album);
            String lastTitle = out.isEmpty() ? null : TitleGuard.canonicalTitle(out.get(out.size() - 1));
            String prevArtist = out.size() >= 2 ? norm(out.get(out.size() - 2).artists) : null;
            String prevAlbum = out.size() >= 2 ? norm(out.get(out.size() - 2).album) : null;
            boolean artistRun = prevArtist != null && prevArtist.equals(lastArtist);
            boolean albumRun = prevAlbum != null && prevAlbum.equals(lastAlbum);

            // discovery gate still random but genre tier now dominates
            List<Track> eligible = new ArrayList<>();
            for (Track t : pool) {
                String a = norm(t.artists);
                if (artistRun && lastArtist != null && a.equals(lastArtist)) continue;
                if (albumRun && lastAlbum != null && norm(t.album).equals(lastAlbum)) continue;
                // Adaptive artist cooldown (see above). Whenever nothing else
                // is eligible the full pool stands in (small pools terminate).
                if (artistWindow > 0 && artistInTail(out, a, artistWindow)) continue;
                // Same-title guard: one song must never follow itself, even as
                // a different artist's cover or version. Falls back to the
                // full pool when nothing else is eligible (all-same-title).
                if (lastTitle != null && !lastTitle.isEmpty()
                        && lastTitle.equals(TitleGuard.canonicalTitle(t))) continue;
                String vf = versionFamily(t);
                if (selectedVersionFamilies.contains(vf)) continue;
                eligible.add(t);
            }
            List<Track> sourceForDiscovery = eligible.isEmpty() ? pool : eligible;
            // weighted discovery branch disabled in favor of genre-tier scoring; keep small random wander
            if (discovery > 0 && random.nextDouble() < discovery * 0.35) {
                Track chosen = pickDiscovery(sourceForDiscovery, lastArtist, lastAlbum, artistRun, albumRun, out, artistWindow);
                pool.remove(chosen);
                out.add(chosen);
                inArtists.merge(norm(chosen.artists), 1, Integer::sum);
                inAlbums.merge(norm(chosen.album), 1, Integer::sum);
                selectedIds.add(chosen.id);
                selectedVersionFamilies.add(versionFamily(chosen));
                GenreHelper.Tier tier = GenreHelper.tier(GenreHelper.genreOf(chosen), seedGenre);
                if (tier == GenreHelper.Tier.SAME) sameCount++; else if (tier == GenreHelper.Tier.RELATED) relatedCount++; else discoveryCount++;
                continue;
            }

            // Mood-first cooldown: the artist gap holds only while an uncooled
            // candidate keeps the current mood (love -> love -> sad -> love).
            // Otherwise mood continuity wins and the gap relaxes — a small
            // same-mood pool must not be force-wandered off-mood.
            boolean uncooledOnMood = false;
            if (artistWindow > 0) {
                for (Track t : pool) {
                    if (!artistInTail(out, norm(t.artists), artistWindow) && keepsMood(t, ctx)) {
                        uncooledOnMood = true;
                        break;
                    }
                }
            }
            double[] weights = new double[pool.size()];
            double total = 0;
            for (int i = 0; i < pool.size(); i++) {
                Track t = pool.get(i);
                double w = score(t, ctx, recentTracks, recentArtists, recentAlbums, inArtists, inAlbums, skipped, liked, out.size(), seedGenre, lastGenre, sameCount, relatedCount, discoveryCount, out.size(), selectedVersionFamilies, isExplicitMood);
                if (artistRun && norm(t.artists).equals(lastArtist)) w = 0;
                if (albumRun && norm(t.album).equals(lastAlbum)) w = 0;
                if (lastTitle != null && !lastTitle.isEmpty()
                        && lastTitle.equals(TitleGuard.canonicalTitle(t))) w = 0;
                // Adaptive artist cooldown (see above).
                if (uncooledOnMood && artistInTail(out, norm(t.artists), artistWindow)) w = 0;
                String vf = versionFamily(t);
                if (selectedVersionFamilies.contains(vf)) w = 0;
                weights[i] = Math.max(0, w);
                total += weights[i];
            }
            if (total <= 0) {
                total = 0;
                boolean hasNonConflicting = false;
                boolean hasUncooled = false;
                for (int i = 0; i < pool.size(); i++) {
                    Track cand = pool.get(i);
                    if (!selectedVersionFamilies.contains(versionFamily(cand))) {
                        hasNonConflicting = true;
                    }
                    if (artistWindow <= 0 || !artistInTail(out, norm(cand.artists), artistWindow)) {
                        hasUncooled = true;
                    }
                }
                for (int i = 0; i < pool.size(); i++) {
                    double w = score(pool.get(i), ctx, recentTracks, recentArtists, recentAlbums, inArtists, inAlbums, skipped, liked, out.size(), seedGenre, lastGenre, sameCount, relatedCount, discoveryCount, out.size(), selectedVersionFamilies, isExplicitMood);
                    if (selectedVersionFamilies.contains(versionFamily(pool.get(i)))) {
                        w = hasNonConflicting ? 0 : 0.0001;
                    } else {
                        w = Math.max(0.0001, w);
                    }
                    // Cooldown holds while any uncooled candidate exists;
                    // a fully cooled pool (one singer) still terminates.
                    if (hasUncooled && artistWindow > 0
                            && artistInTail(out, norm(pool.get(i).artists), artistWindow)) {
                        w = 0;
                    }
                    weights[i] = Math.max(0, w);
                    total += weights[i];
                }
            }
            int pickIdx = pick(weights, total);
            Track chosen = pool.remove(pickIdx);
            out.add(chosen);
            inArtists.merge(norm(chosen.artists), 1, Integer::sum);
            inAlbums.merge(norm(chosen.album), 1, Integer::sum);
            selectedIds.add(chosen.id);
            selectedVersionFamilies.add(versionFamily(chosen));
            GenreHelper.Tier tier = GenreHelper.tier(GenreHelper.genreOf(chosen), seedGenre);
            if (tier == GenreHelper.Tier.SAME) sameCount++; else if (tier == GenreHelper.Tier.RELATED) relatedCount++; else discoveryCount++;
            if (out.size() > 3) {
                Track old = out.get(out.size() - 4);
                dec(inArtists, norm(old.artists));
                dec(inAlbums, norm(old.album));
            }
        }
        // Final invariant: no two adjacent tracks share a canonical title
        // (covers/versions of one song), whenever the pool allows separation.
        // Same multiset, greedy-stable reorder — scoring order is preserved
        // except where a same-title run must be broken.
        return TitleGuard.decluster(out, 0);
    }



    private double score(Track t, ListeningContext ctx,
                         Map<String, Integer> recentTracks,
                         Map<String, Integer> recentArtists,
                         Map<String, Integer> recentAlbums,
                         Map<String, Integer> inArtists,
                         Map<String, Integer> inAlbums,
                         Set<String> skipArtists,
                         Set<String> likedIds,
                         int position,
                         String seedGenre, String lastGenre,
                         int sameCount, int relatedCount, int discoveryCount, int outSize,
                         Set<String> selectedVersionFamilies, boolean isExplicitMood) {
        String title = ListeningContext.norm(t.name);
        String artist = norm(t.artists);
        String album = norm(t.album);

        double mood = 0;
        if (ctx.primaryMood != null) {
            String cand = ListeningContext.detectMood(title + " " + artist + " " + album);
            Map<String, Double> blend = ListeningContext.BLENDS.get(ctx.primaryMood);
            Double value = cand != null && blend != null ? blend.get(cand) : null;
            if (value != null) {
                if (isExplicitMood) mood = value * 0.92;
                else mood = value * (0.35 + 0.65 * ctx.moodConfidence);
            }
        }
        double seedArtist = artist.equals(norm(ctx.seedArtist)) ? 0.20 : 0;
        double seedAlbum = album.equals(norm(ctx.seedAlbum)) ? 0.12 : 0;
        double genre = 0.06 * Math.min(3, keywordOverlap(album, norm(ctx.seedAlbum)));
        double like = likedIds.contains(t.id) ? 0.12 : 0;
        double fame = 0;
        if (t.playCount > 0) {
            double log = Math.log10((double) t.playCount + 1.0);
            fame = Math.min(0.30, Math.max(0, (log - 3.0) * 0.06));
        }
        // Genre tier dominance: 70-80% same, 15-25% related — toned down when explicit mood dominates
        String candGenre = GenreHelper.genreOf(t);
        GenreHelper.Tier tier = GenreHelper.tier(candGenre, seedGenre);
        double genreTier = 0;
        if (isExplicitMood && ctx.primaryMood != null) {
            String candMood = ListeningContext.detectMood(t.name + " " + t.artists + " " + t.album);
            Map<String, Double> blend = ListeningContext.BLENDS.get(ctx.primaryMood);
            if (blend != null && candMood != null && blend.containsKey(candMood)) {
                // Explicit mood matches candidate: upgrade tier
                if (tier == GenreHelper.Tier.RELATED) genreTier = 0.10;
                else if (tier == GenreHelper.Tier.DISCOVERY) genreTier = 0.05;
                else genreTier = 0.16; // Already SAME, keep positive
            } else {
                // Explicit mood active but doesn't match candidate
                genreTier = -0.08;
            }
        } else if (!isExplicitMood) {
            if (tier == GenreHelper.Tier.SAME) genreTier = 0.38;
            else if (tier == GenreHelper.Tier.RELATED) genreTier = 0.15;
            else genreTier = -0.18;
        }

        // Enforce tier percentages via dynamic penalty
        int total = sameCount + relatedCount + discoveryCount;
        if (total > 0) {
            double sameRatio = sameCount / (double) total;
            double discRatio = discoveryCount / (double) total;
            if (tier == GenreHelper.Tier.SAME && sameRatio > 0.80) genreTier -= 0.22;
            if (tier == GenreHelper.Tier.DISCOVERY && discRatio > 0.10) genreTier -= 0.45;
            if (tier == GenreHelper.Tier.DISCOVERY && outSize < 5) genreTier -= 0.30;
        }

        // Sequential continuity vs last track
        double continuity = 0;
        if (lastGenre != null) {
            if (candGenre.equals(lastGenre)) continuity += 0.12;
            else if (GenreHelper.isRelated(candGenre, lastGenre)) continuity += 0.06;
        }

        double moodWeight = isExplicitMood ? 0.55 : 0.30;
        double relevance = Math.min(1.0, moodWeight * mood + seedArtist + seedAlbum + genre + like + fame + genreTier + continuity + 0.12);

        double penalty = 0;
        if (recentTracks.containsKey(t.id)) penalty += 1.6;
        int ar = recentArtists.getOrDefault(artist, 0) + inArtists.getOrDefault(artist, 0);
        if (ar > 0) penalty += 0.40 * Math.min(2, ar);
        int al = recentAlbums.getOrDefault(album, 0) + inAlbums.getOrDefault(album, 0);
        if (al > 0) penalty += 0.20 * Math.min(2, al);
        if (skipArtists.contains(artist)) penalty += 0.50;
        if (selectedVersionFamilies.contains(versionFamily(t))) penalty += 0.90;
        // Recent track strong penalty already, but add position decay
        double cool = Math.max(0.22, 1.0 - position * 0.025);
        return Math.pow(Math.max(0.02, relevance - penalty) * cool, TEMPERATURE) + 0.0001;
    }

    private int pick(double[] weights, double total) {
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r <= 0) return i;
        }
        return weights.length - 1;
    }

    private Track pickDiscovery(List<Track> pool, String lastArtist, String lastAlbum, boolean artistRun, boolean albumRun, List<Track> out, int artistWindow) {
        List<Track> eligible = new ArrayList<>();
        String lastTitle = out.isEmpty() ? null : TitleGuard.canonicalTitle(out.get(out.size() - 1));
        for (Track t : pool) {
            String a = norm(t.artists);
            if (artistRun && lastArtist != null && a.equals(lastArtist)) continue;
            if (albumRun && lastAlbum != null && norm(t.album).equals(lastAlbum)) continue;
            if (artistWindow > 0 && artistInTail(out, a, artistWindow)) continue;
            if (lastTitle != null && !lastTitle.isEmpty()
                    && lastTitle.equals(TitleGuard.canonicalTitle(t))) continue;
            eligible.add(t);
        }
        List<Track> source = eligible.isEmpty() ? pool : eligible;
        return source.get(random.nextInt(source.size()));
    }

    private static final int ARTIST_COOLDOWN = 8;

    private static int countArtistInTail(List<Track> out, String artist, int tail) {
        if (artist == null) return 0;
        int n = 0;
        for (int i = Math.max(0, out.size() - tail); i < out.size(); i++) if (artist.equals(norm(out.get(i).artists))) n++;
        return n;
    }

    /** True when the candidate keeps the session mood (or no mood is set). */
    private static boolean keepsMood(Track t, ListeningContext ctx) {
        if (ctx == null || ctx.primaryMood == null) return true;
        String cand = ListeningContext.detectMood(
                (t.name != null ? t.name : "") + " "
                        + (t.artists != null ? t.artists : "") + " "
                        + (t.album != null ? t.album : ""));
        if (cand == null) return false;
        Map<String, Double> blend = ListeningContext.BLENDS.get(ctx.primaryMood);
        return blend != null && blend.containsKey(cand);
    }

    /** True when the artist sang anything in the last {@code window} picks. */
    private static boolean artistInTail(List<Track> out, String artist, int window) {
        if (artist == null || artist.isEmpty()) return false;
        for (int i = Math.max(0, out.size() - window); i < out.size(); i++) {
            if (artist.equals(norm(out.get(i).artists))) return true;
        }
        return false;
    }

    private static void dec(Map<String, Integer> map, String key) { map.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null); }

    private static final Set<String> GENERIC_TOKENS = new HashSet<>(Arrays.asList("album","albums","vol","volume","edition","deluxe","version","remastered","best","greatest","hits","collection","singles","the"));

    private static int keywordOverlap(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<String> wa = new HashSet<>();
        for (String w : a.split(" ")) if (w.length() >= 4 && !GENERIC_TOKENS.contains(w)) wa.add(w);
        int hits = 0;
        for (String w : b.split(" ")) if (w.length() >= 4 && !GENERIC_TOKENS.contains(w) && wa.contains(w)) hits++;
        return hits;
    }

    private static String norm(String raw) { return ListeningContext.norm(raw); }

    private static String versionFamily(Track t) {
        if (t == null || t.name == null) return "";
        String title = ListeningContext.norm(t.name);
        // strip version keywords to get base title
        String base = title.replaceAll("\\b(acoustic|live|remix|remastered|reimagined|version|cover|instrumental|edit)\\b", "").trim().replaceAll("\\s+", " ");
        String artist = norm(t.artists);
        return base + "::" + artist;
    }
}

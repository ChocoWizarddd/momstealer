package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.TitleGuard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavior contracts for the recommendation pipeline (§31):
 * signals, profiles, candidate generation, scoring, MMR, sequential
 * selection, search-vs-smart separation (§18) and the §19 acceptance style.
 */
public class RecommendationTest {

    private static Track track(String id, String name, String artist, String genre) {
        return new Track(id, name, artist, "Album " + artist, "", "https://x/y.mp3",
                200_000L, "2020", 0L, "english", genre);
    }

    private static Track pop(String id, String name, String artist) {
        return track(id, name, artist, "Pop");
    }

    // ---- §7 behavioral signals ----

    @Test
    public void signals_gradePlaybackStrength() {
        assertEquals(Signal.EARLY_SKIP, Signal.fromPlayback(0.05, true, false));
        assertEquals(Signal.SKIP, Signal.fromPlayback(0.2, true, false));
        assertEquals(Signal.PARTIAL, Signal.fromPlayback(0.6, true, false));
        assertEquals(Signal.COMPLETED_80, Signal.fromPlayback(0.9, true, false));
        assertEquals(Signal.COMPLETED_50, Signal.fromPlayback(0.6, false, false));
        assertEquals(Signal.COMPLETED_80, Signal.fromPlayback(0.85, false, false));
        assertEquals(Signal.FULL, Signal.fromPlayback(1.0, false, false));
        assertEquals(Signal.REPLAY, Signal.fromPlayback(0.5, false, true));
        assertTrue("ordering: like > full > 80 > 50 > partial > skip > early",
                Signal.LIKE.weight > Signal.FULL.weight
                        && Signal.FULL.weight > Signal.COMPLETED_80.weight
                        && Signal.COMPLETED_80.weight > Signal.COMPLETED_50.weight
                        && Signal.COMPLETED_50.weight > Signal.PARTIAL.weight
                        && Signal.PARTIAL.weight > Signal.SKIP.weight
                        && Signal.SKIP.weight > Signal.EARLY_SKIP.weight);
        assertTrue(Signal.UNLIKE.weight < 0);
        assertTrue(Signal.COMPLETED_80.isSuccess());
        assertFalse(Signal.PARTIAL.isSuccess());
    }

    // ---- §8 session profile: recency wins ----

    @Test
    public void session_recentBehaviorOutweighsOld() {
        SessionProfile s = new SessionProfile();
        Track rock = track("r1", "Rock Song", "Rock Band", "Rock");
        Track popT = track("p1", "Pop Song", "Pop Star", "Pop");
        // Equal counts, pop is newer: recency wins.
        for (int i = 0; i < 3; i++) s.record(rock, Signal.FULL);
        for (int i = 0; i < 3; i++) s.record(popT, Signal.FULL);
        assertTrue(s.genreAffinity("pop") > s.genreAffinity("rock"));
        // Volume still matters: 6 rock completions outweigh 2 pop ones.
        SessionProfile v = new SessionProfile();
        for (int i = 0; i < 6; i++) v.record(rock, Signal.FULL);
        for (int i = 0; i < 2; i++) v.record(popT, Signal.FULL);
        assertTrue(v.genreAffinity("rock") > v.genreAffinity("pop"));
        assertEquals(8, v.size());
        assertEquals(8, v.recentSuccesses(10).size());
        assertTrue(v.hasPlayed("r1"));
        assertFalse(v.hasPlayed("nope"));
    }

    @Test
    public void session_recordsSkips() {
        SessionProfile s = new SessionProfile();
        s.record(track("a", "Song A", "Skipped Artist", "Pop"), Signal.EARLY_SKIP);
        assertTrue(s.recentSkips().contains("skipped artist"));
    }

    // ---- §6 taste profile: aggregates, bounds, serialization ----

    @Test
    public void taste_learnsAndPrefersLikedGenre() {
        TasteProfile t = new TasteProfile();
        for (int i = 0; i < 5; i++) {
            t.update(pop("p" + i, "Pop " + i, "Pop Star"), Signal.FULL);
        }
        t.update(track("r1", "Rock 1", "Rock Band", "Rock"), Signal.EARLY_SKIP);
        assertTrue(t.genreAffinity("pop") > t.genreAffinity("rock"));
        assertTrue(t.artistAffinity("Pop Star") > 0.5);
        assertEquals("pop star", t.topArtists(1).get(0));
        t.update(pop("px", "Liked One", "Pop Star"), Signal.LIKE);
        assertTrue(t.isLiked("px"));
        t.update(pop("px", "Liked One", "Pop Star"), Signal.UNLIKE);
        assertFalse(t.isLiked("px"));
    }

    @Test
    public void taste_serializationRoundTrip() {
        TasteProfile t = new TasteProfile();
        t.update(pop("p1", "Pop 1", "Pop Star"), Signal.LIKE);
        t.update(track("r1", "Rock 1", "Rock Band", "Rock"), Signal.FULL);
        t.addPlaytimeMs(90_000);
        TasteProfile u = new TasteProfile();
        u.fromMap(t.toMap());
        assertEquals(t.genreAffinity("pop"), u.genreAffinity("pop"), 1e-3);
        assertEquals(t.artistAffinity("Pop Star"), u.artistAffinity("Pop Star"), 1e-3);
        assertTrue(u.isLiked("p1"));
        assertEquals(90_000L, u.totalPlaytimeMs());
    }

    // ---- §4/5 candidate pool: labels + dedupe ----

    @Test
    public void pool_labelsSourcesAndDedupesFamily() {
        Track seed = pop("seed", "Perfect", "Ed Sheeran");
        CandidatePool.Input in = new CandidatePool.Input();
        in.seed = seed;
        in.radio.add(pop("v1", "Perfect Acoustic", "Ed Sheeran")); // same family as seed
        in.radio.add(pop("t1", "Photograph", "Ed Sheeran"));       // same artist, own family
        in.radio.add(pop("t2", "Thinking Out Loud", "Ed Sheeran"));
        in.liked.add(pop("t1", "Photograph", "Ed Sheeran"));       // multi-source
        in.trending.add(track("m1", "Metal Song", "Metal Band", "Metal"));

        List<Candidate> pool = CandidatePool.build(in, new SessionProfile(), new TasteProfile());
        Set<String> ids = new HashSet<>();
        for (Candidate c : pool) ids.add(c.track.id);
        assertFalse("seed version family excluded", ids.contains("v1"));
        assertTrue(ids.contains("t1"));
        assertTrue(ids.contains("t2"));
        assertTrue(ids.contains("m1"));
        for (Candidate c : pool) {
            if (c.track.id.equals("t1")) {
                assertTrue("multi-sourced", c.sources.size() >= 2);
                assertTrue(c.sources.contains(Candidate.Source.LIKED_NEIGHBORHOOD));
            }
            if (c.track.id.equals("m1")) {
                assertTrue(c.sources.contains(Candidate.Source.TRENDING));
            }
        }
    }

    // ---- §12 relevance: transparent + popularity light ----

    @Test
    public void scorer_prefersRelevantOverFamous() {
        Track seed = pop("seed", "Perfect", "Ed Sheeran");
        TasteProfile taste = new TasteProfile();
        taste.update(pop("p1", "Pop Hit", "Pop Star"), Signal.LIKE);
        RelevanceScorer scorer = new RelevanceScorer();
        RelevanceScorer.Context ctx = new RelevanceScorer.Context();
        ctx.seed = seed;
        ctx.taste = taste;
        ctx.session = new SessionProfile();
        ctx.mode = DiscoveryMode.BALANCED;

        Track relevant = new Track("rel", "Soft Song", "Pop Star", "Al", "",
                "https://x/y.mp3", 200_000L, "2020", 10L, "english", "Pop");
        Track famous = new Track("fam", "Metal Hit", "Metal Band", "Al", "",
                "https://x/y.mp3", 200_000L, "2020", 50_000_000L, "english", "Metal");
        double relScore = scorer.score(new Candidate(relevant,
                Candidate.Source.RADIO_SEED, ""), ctx).total;
        double famScore = scorer.score(new Candidate(famous,
                Candidate.Source.RADIO_SEED, ""), ctx).total;
        assertTrue("relevant unknown beats famous irrelevant: " + relScore + " vs " + famScore,
                relScore > famScore);
    }

    // ---- §13 MMR: Artist A x8 spreads ----

    @Test
    public void mmr_preventsArtistWall() {
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pool.add(new Candidate(pop("a" + i, "Song " + i, "Artist A"),
                    Candidate.Source.RADIO_SEED, ""));
        }
        String[] others = {"B", "C", "D", "E", "F", "G", "H", "I"};
        for (int i = 0; i < others.length; i++) {
            pool.add(new Candidate(pop("b" + i, "Other " + i, "Artist " + others[i]),
                    Candidate.Source.RADIO_SEED, ""));
        }
        java.util.Map<String, Double> rel = new java.util.HashMap<>();
        for (Candidate c : pool) rel.put(c.track.id, 0.8); // identical relevance
        List<MmrRanker.Ranked> ranked = MmrRanker.rank(pool, new ArrayList<Track>(),
                rel, DiscoveryMode.BALANCED.mmrLambda, 10, new SessionProfile());
        assertEquals(10, ranked.size());
        int maxRun = 1, cur = 1;
        Set<String> artists = new HashSet<>();
        for (MmrRanker.Ranked r : ranked) artists.add(r.candidate.track.artists);
        for (int i = 1; i < ranked.size(); i++) {
            if (ranked.get(i).candidate.track.artists.equals(
                    ranked.get(i - 1).candidate.track.artists)) {
                cur++;
                maxRun = Math.max(maxRun, cur);
            } else {
                cur = 1;
            }
        }
        assertTrue("max same-artist run <= 2, was " + maxRun, maxRun <= 2);
        assertTrue("diverse artists, was " + artists.size(), artists.size() >= 4);
    }

    // ---- §14 sequential: D feels A/B/C ----

    @Test
    public void sequential_trailChangesLaterPicks() {
        RecommendationEngine engine = new RecommendationEngine();
        Track seed = pop("seed", "Perfect", "Ed Sheeran");
        List<Track> radio = new ArrayList<>();
        for (int i = 0; i < 10; i++) radio.add(pop("p" + i, "Pop " + i, "Pop Artist " + i));
        for (int i = 0; i < 10; i++) {
            radio.add(track("r" + i, "Rock " + i, "Rock Band " + i, "Rock"));
        }
        RecommendationEngine.Request base = new RecommendationEngine.Request();
        base.seed = seed;
        base.pool.seed = seed;
        base.pool.radio = radio;
        base.session = new SessionProfile();
        base.taste = new TasteProfile();
        base.mode = DiscoveryMode.BALANCED;
        base.count = 6;

        List<Track> batch1 = engine.nextBatch(base).tracks;
        assertEquals(6, batch1.size());

        RecommendationEngine.Request cont = new RecommendationEngine.Request();
        cont.seed = seed;
        cont.pool.seed = seed;
        cont.pool.radio = radio;
        cont.session = new SessionProfile();
        cont.taste = new TasteProfile();
        cont.mode = DiscoveryMode.BALANCED;
        cont.count = 6;
        cont.contextTrail = new ArrayList<>();
        cont.contextTrail.add(seed);
        cont.contextTrail.addAll(batch1);
        Set<String> exclude = new HashSet<>();
        for (Track t : batch1) exclude.add(t.id);
        cont.excludeIds = exclude;
        List<Track> batch2 = engine.nextBatch(cont).tracks;
        assertEquals(6, batch2.size());
        for (Track t : batch2) assertFalse(exclude.contains(t.id));
    }

    // ---- §18 search-vs-smart separation ----

    @Test
    public void searchResults_areNotTheSession() {
        // Search wall for one title (what the search screen shows).
        List<Track> wall = new ArrayList<>();
        String[] variants = {"Perfect", "Perfect Live", "Perfect Acoustic",
                "Perfect Remix", "Perfect (Cover)", "Perfect - Slowed"};
        for (int i = 0; i < variants.length; i++) {
            wall.add(pop("w" + i, variants[i], "Cover Artist " + i));
        }
        // Fresh provider candidates (what radio/trending return).
        List<Track> fresh = new ArrayList<>();
        String[] names = {"Photograph", "Shape of You", "Thinking Out Loud",
                "Blinding Lights", "Levitating", "Kesariya", "Tum Hi Ho",
                "Apna Time Aayega", "Senorita", "Believer", "Demons", "Counting Stars"};
        String[] artists = {"Ed Sheeran", "Ed Sheeran", "Ed Sheeran", "The Weeknd",
                "Dua Lipa", "Arijit Singh", "Arijit Singh", "Ranveer Singh",
                "Shawn Mendes", "Imagine Dragons", "Imagine Dragons", "OneRepublic"};
        String[] genres = {"Pop", "Pop", "Pop", "R&B", "Pop", "Romantic", "Romantic",
                "Hip-Hop", "Pop", "Rock", "Rock", "Pop"};
        for (int i = 0; i < names.length; i++) {
            fresh.add(track("f" + i, names[i], artists[i], genres[i]));
        }

        Track tapped = wall.get(0); // user plays Perfect (cover artist 0)
        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = tapped;
        req.pool.seed = tapped;
        req.pool.radio = fresh; // radio returns OTHER songs, never the wall
        req.session = new SessionProfile();
        req.taste = new TasteProfile();
        req.mode = DiscoveryMode.BALANCED;
        req.count = 12;
        List<Track> session = engine.newSession(req).tracks;

        int wallCount = 0;
        Set<String> freshIds = new HashSet<>();
        for (Track t : fresh) freshIds.add(t.id);
        int freshCount = 0;
        for (Track t : session) {
            if (freshIds.contains(t.id)) freshCount++;
            else if (TitleGuard.canonicalTitle(t.name).equals("perfect")) wallCount++;
        }
        assertTrue("session is NEW recommendations, fresh=" + freshCount + " wall=" + wallCount,
                freshCount >= 8 && wallCount <= 2);
        assertEquals(0, TitleGuard.countAdjacentSameTitle(session));
    }

    // ---- §19 acceptance style: Perfect seed ----

    @Test
    public void acceptance_perfectSeedYieldsPopWorldNotVariants() {
        Track seed = pop("seed", "Perfect", "Ed Sheeran");
        List<Track> radio = new ArrayList<>();
        radio.add(pop("v1", "Perfect Acoustic", "Ed Sheeran"));
        radio.add(pop("v2", "Perfect Live", "Ed Sheeran"));
        radio.add(track("c1", "Perfect (Piano Cover)", "Piano Guys", "Pop"));
        String[] names = {"Photograph", "Shape of You", "All of Me", "Let Her Go",
                "Stay With Me", "Skinny Love", "Riptide", "Ho Hey",
                "Senorita", "Treat You Better", "Mercy", "Dive",
                "Perfect Duet", "Thinking Out Loud", "Happier", "Someone Like You",
                "A Thousand Years", "Let It Go", "Count on Me", "Lovely"};
        String[] artists = {"Ed Sheeran", "Ed Sheeran", "John Legend", "Passenger",
                "Sam Smith", "Bon Iver", "Vance Joy", "Lumineers",
                "Shawn Mendes", "Shawn Mendes", "Shawn Mendes", "Ed Sheeran",
                "Beyonce", "Ed Sheeran", "Ed Sheeran", "Adele",
                "Christina Perri", "James Bay", "Bruno Mars", "Billie Eilish"};
        for (int i = 0; i < names.length; i++) radio.add(pop("p" + i, names[i], artists[i]));
        radio.add(track("x1", "Metal Hit", "Metal Band", "Metal"));
        radio.add(track("x2", "Hard Drop", "EDM Act", "EDM"));
        radio.add(track("x3", "Random Punjabi Track", "Unknown MC", "Hip-Hop"));

        TasteProfile taste = new TasteProfile();
        taste.update(pop("l1", "All of Me", "John Legend"), Signal.LIKE);
        taste.update(pop("l2", "Photograph", "Ed Sheeran"), Signal.FULL);

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = radio;
        req.session = new SessionProfile();
        req.taste = taste;
        req.mode = DiscoveryMode.BALANCED;
        req.count = 15;
        RecommendationEngine.Result res = engine.newSession(req);

        assertEquals(15, res.tracks.size());
        int edCount = 0, popFam = 0, bad = 0, maxRun = 1, cur = 1;
        Set<String> fams = new HashSet<>();
        fams.add(CandidatePool.versionFamily(seed));
        for (int i = 0; i < res.tracks.size(); i++) {
            Track t = res.tracks.get(i);
            assertFalse("seed version family banned: " + t.name,
                    fams.contains(CandidatePool.versionFamily(t)));
            if (t.artists.equals("Ed Sheeran")) edCount++;
            String g = com.nightlight.app.smartshuffle.GenreHelper.genreOf(t);
            if (g.equals("pop") || com.nightlight.app.smartshuffle.GenreHelper.isRelated(g, "pop")) popFam++;
            if (g.equals("electronic")) bad++;
            if (i > 0) {
                if (t.artists.equals(res.tracks.get(i - 1).artists)) {
                    cur++;
                    maxRun = Math.max(maxRun, cur);
                } else {
                    cur = 1;
                }
            }
        }
        assertTrue("no Ed Sheeran wall, was " + edCount, edCount <= 3);
        assertTrue("no artist wall, run was " + maxRun, maxRun <= 2);
        assertTrue("Pop-family majority, was " + popFam + "/15", popFam >= 9);
        assertEquals("no genre whiplash", 0, bad);
        assertFalse("reasons recorded", res.reasons.isEmpty());
    }

    // ---- §15 familiarity vs discovery differ ----

    @Test
    public void discovery_broadensBeyondFamiliar() {
        Track seed = pop("seed", "Perfect", "Ed Sheeran");
        List<Track> radio = new ArrayList<>();
        for (int i = 0; i < 6; i++) radio.add(pop("p" + i, "Pop " + i, "Pop Artist"));
        for (int i = 0; i < 6; i++) {
            radio.add(track("b" + i, "Soft Rock " + i, "Rock Band", "Rock"));
        }
        TasteProfile taste = new TasteProfile();
        for (int i = 0; i < 4; i++) taste.update(pop("l" + i, "Pop Hit", "Pop Artist"), Signal.LIKE);

        RecommendationEngine engine = new RecommendationEngine();
        java.util.function.Function<DiscoveryMode, Integer> rockCount = mode -> {
            RecommendationEngine.Request req = new RecommendationEngine.Request();
            req.seed = seed;
            req.pool.seed = seed;
            req.pool.radio = new ArrayList<>(radio);
            req.session = new SessionProfile();
            req.taste = taste;
            req.mode = mode;
            req.count = 8;
            int n = 0;
            for (Track t : engine.newSession(req).tracks) {
                if (com.nightlight.app.smartshuffle.GenreHelper.genreOf(t).equals("rock")) n++;
            }
            return n;
        };
        assertTrue("discovery >= familiar rock picks",
                rockCount.apply(DiscoveryMode.DISCOVERY) >= rockCount.apply(DiscoveryMode.FAMILIAR));
    }

    @Test
    public void pool_emptyWithoutSeed() {
        CandidatePool.Input in = new CandidatePool.Input();
        assertTrue(CandidatePool.build(in, new SessionProfile(), new TasteProfile()).isEmpty());
        assertTrue(new RecommendationEngine().nextBatch(new RecommendationEngine.Request()).tracks.isEmpty());
    }
}

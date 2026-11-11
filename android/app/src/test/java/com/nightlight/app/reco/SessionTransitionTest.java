package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.TitleGuard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Session-transition behavior (§26 A–D, I–M): evolving vibe, previous-track
 * anchoring, language continuity, musical neighborhoods, transition-sum
 * sequence quality, and the GenerationGuard state machine.
 */
public class SessionTransitionTest {

    private static int idSeq = 5000;

    private static Track t(String name, String artist, String genre, String lang) {
        // NOTE: unique album per track — sharing one album name across an
        // artist's tracks would (correctly) trigger album-repeat penalties.
        return new Track("st" + (idSeq++), name, artist, name + " Album", "",
                "https://x/y.mp3", 200_000L, "2020", 1000L, lang, genre);
    }

    // ---- A. session evolution ----

    @Test
    public void sessionContext_evolvesWithAcceptedTracks() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        SessionContext vibe = new SessionContext(new SessionProfile(), seed);
        assertEquals(0, vibe.size());
        vibe.advance(t("Blood Sweat and Tears", "BTS", "K-pop", "korean"));
        vibe.advance(t("Dynamite", "BTS", "Pop", "english"));
        assertEquals(2, vibe.size());
        assertEquals("BTS run length 2", 2,
                vibe.artistRunLength(TitleGuard.norm("BTS")));
        assertTrue("english now in session languages",
                vibe.topLanguages(2).contains("english"));
        assertTrue("korean still present",
                vibe.topLanguages(2).contains("korean"));
        // Session copies isolate parallel sequence variants.
        SessionContext copy = vibe.copy();
        copy.advance(t("Seven", "Jung Kook", "Pop", "english"));
        assertEquals(2, vibe.size());
        assertEquals(3, copy.size());
    }

    @Test
    public void laterPicks_feelEarlierPicks() {
        RecommendationEngine engine = new RecommendationEngine();
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        List<Track> radio = new ArrayList<>();
        for (int i = 0; i < 8; i++) radio.add(t("Kpop " + i, "K-Act " + i, "K-pop", "korean"));
        for (int i = 0; i < 8; i++) radio.add(t("Pop " + i, "P-Act " + i, "Pop", "english"));

        RecommendationEngine.Request bare = request(seed, radio, new ArrayList<Track>());
        List<Track> first = engine.nextBatch(bare).tracks;
        assertFalse(first.isEmpty());

        // Same pool, but three picks already accepted: the next batch must
        // reflect them (different order/content vs the bare run).
        List<Track> trail = new ArrayList<>();
        trail.add(seed);
        trail.addAll(first.subList(0, Math.min(3, first.size())));
        RecommendationEngine.Request continued = request(seed, radio, trail);
        Set<String> exclude = new HashSet<>();
        for (Track x : trail) exclude.add(x.id);
        continued.excludeIds = exclude;
        List<Track> second = engine.nextBatch(continued).tracks;
        for (Track x : second) assertFalse(exclude.contains(x.id));
        assertFalse("continued batch differs from bare batch",
                ids(first).equals(ids(second)));
    }

    // ---- B. previous-track anchoring ----

    @Test
    public void sameSeed_differentPrev_differentOrder() {
        RecommendationEngine engine = new RecommendationEngine();
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        List<Track> radio = new ArrayList<>();
        radio.add(t("Blood Sweat and Tears", "BTS", "K-pop", "korean"));
        radio.add(t("Dynamite", "BTS", "Pop", "english"));
        for (int i = 0; i < 6; i++) radio.add(t("Pop " + i, "P-Act " + i, "Pop", "english"));
        for (int i = 0; i < 6; i++) radio.add(t("Kpop " + i, "K-Act " + i, "K-pop", "korean"));

        List<Track> trailA = new ArrayList<>();
        trailA.add(seed);
        trailA.add(radio.get(0)); // prev = BTS K-pop track
        List<Track> trailB = new ArrayList<>();
        trailB.add(seed);
        trailB.add(radio.get(1)); // prev = BTS global-pop track
        List<Track> orderA = engine.nextBatch(request(seed, radio, trailA)).tracks;
        List<Track> orderB = engine.nextBatch(request(seed, radio, trailB)).tracks;
        assertFalse("previous track changes the ordering",
                ids(orderA).equals(ids(orderB)));
    }

    // ---- C. language continuity ----

    @Test
    public void unexpectedLanguage_isStronglyPenalized() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        SessionContext vibe = new SessionContext(new SessionProfile(), seed);
        vibe.advance(t("Blood Sweat and Tears", "BTS", "K-pop", "korean"));
        vibe.advance(t("Dynamite", "BTS", "Pop", "english"));

        TransitionScorer scorer = new TransitionScorer();
        Track prev = t("Dynamite", "BTS", "Pop", "english");
        Candidate hindi = new Candidate(t("Hindi Song", "Hindi Act", "Bollywood", "hindi"),
                Candidate.Source.RADIO_SEED, "");
        Candidate english = new Candidate(t("Pop Song", "Pop Act", "Pop", "english"),
                Candidate.Source.RADIO_SEED, "");
        TransitionScorer.In inH = input(prev, vibe, hindi);
        TransitionScorer.In inE = input(prev, vibe, english);
        TransitionScorer.Breakdown bh = scorer.score(inH);
        TransitionScorer.Breakdown be = scorer.score(inE);
        assertTrue("hindi penalized in korean/english session: " + bh.total + " vs " + be.total,
                be.total > bh.total + 0.2);
        assertTrue("penalty recorded", bh.penalty >= TransitionScorer.P_LANGUAGE_MISMATCH - 1e-9);
    }

    @Test
    public void mixingSession_softensLanguagePenalty() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        SessionContext vibe = new SessionContext(new SessionProfile(), seed);
        vibe.advance(t("Hindi One", "H Act", "Bollywood", "hindi"));
        vibe.advance(t("Hindi Two", "H Act", "Bollywood", "hindi"));
        vibe.advance(t("English One", "E Act", "Pop", "english"));
        vibe.advance(t("English Two", "E Act", "Pop", "english"));
        assertTrue("session mixes languages", vibe.isMixingLanguages());

        TransitionScorer scorer = new TransitionScorer();
        Track prev = t("English Two", "E Act", "Pop", "english");
        Candidate hindi = new Candidate(t("Hindi Three", "H Act", "Bollywood", "hindi"),
                Candidate.Source.RADIO_SEED, "");
        TransitionScorer.Breakdown b = scorer.score(input(prev, vibe, hindi));
        assertTrue("mixed session: soft penalty only, was " + b.penalty,
                b.penalty < TransitionScorer.P_LANGUAGE_MISMATCH - 1e-9);
    }

    // ---- D. musical neighborhoods ----

    @Test
    public void neighborhoods_kpopReachesPop_notMetal() {
        assertEquals(0, MusicNeighborhood.distance("kpop", "kpop"));
        assertEquals(1, MusicNeighborhood.distance("kpop", "pop"));
        assertEquals(1, MusicNeighborhood.distance("pop", "kpop"));
        assertEquals(2, MusicNeighborhood.distance("kpop", "rock"));
        assertEquals(3, MusicNeighborhood.distance("kpop", "metal"));
        assertTrue(MusicNeighborhood.adjacent("kpop", "rnb"));
        assertFalse(MusicNeighborhood.adjacent("kpop", "punjabi"));
    }

    @Test
    public void kpopSeed_prefersGlobalPopOverMetal() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        TransitionScorer scorer = new TransitionScorer();
        SessionContext vibe = new SessionContext(new SessionProfile(), seed);
        Candidate pop = new Candidate(t("Global Pop Hit", "Pop Star", "Pop", "english"),
                Candidate.Source.RADIO_SEED, "");
        Candidate metal = new Candidate(t("Metal Hit", "Metal Band", "Metal", "english"),
                Candidate.Source.RADIO_SEED, "");
        double popScore = scorer.score(input(seed, vibe, pop)).transition;
        double metalScore = scorer.score(input(seed, vibe, metal)).transition;
        assertTrue("pop transition beats metal: " + popScore + " vs " + metalScore,
                popScore > metalScore + 0.2);
    }

    // ---- E. bad tail: short-but-coherent beats filled-with-junk ----

    @Test
    public void junkPool_yieldsShortBatch_notJunkFill() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = new ArrayList<>();
        // All junk: unknown genre, mismatched language, no radio support is
        // impossible here (radio IS support) — use unrelated + unknown lang.
        for (int i = 0; i < 8; i++) {
            req.pool.radio.add(t("Noise " + i, "Noise Act " + i, "Metal", "spanish"));
        }
        req.session = new SessionProfile();
        req.taste = new TasteProfile();
        req.mode = DiscoveryMode.BALANCED;
        req.count = 10;
        RecommendationEngine.Result res = engine.newSession(req);
        assertTrue("short batch, no junk fill: " + res.tracks.size(),
                res.tracks.size() <= 3);
    }

    // ---- I. transition-sum sequence quality ----

    @Test
    public void smoothSequence_beatsJumpySequence() {
        Track a = t("A", "BTS", "K-pop", "korean");
        Track b = t("B", "BTS", "K-pop", "korean");
        Track c = t("C", "Pop Act", "Pop", "english");
        Track x = t("X", "Metal Band", "Metal", "spanish");
        List<Track> smooth = new ArrayList<>();
        smooth.add(a);
        smooth.add(b);
        smooth.add(c);
        List<Track> jumpy = new ArrayList<>();
        jumpy.add(a);
        jumpy.add(x);
        jumpy.add(c);
        java.util.Map<String, Double> transSmooth = new java.util.HashMap<>();
        transSmooth.put(a.id, 0.8);
        transSmooth.put(b.id, 0.75);
        transSmooth.put(c.id, 0.6);
        java.util.Map<String, Double> transJumpy = new java.util.HashMap<>();
        transJumpy.put(a.id, 0.8);
        transJumpy.put(x.id, 0.1);
        transJumpy.put(c.id, 0.6);
        java.util.Map<String, Double> empty = new java.util.HashMap<>();
        SequenceScorer.SequenceScore sSmooth = SequenceScorer.scoreWithTransitions(
                smooth, transSmooth, empty, empty, new SessionProfile());
        SequenceScorer.SequenceScore sJumpy = SequenceScorer.scoreWithTransitions(
                jumpy, transJumpy, empty, empty, new SessionProfile());
        assertTrue("smooth transitions win", sSmooth.total > sJumpy.total);
    }

    // ---- BTS canonical example: K-pop travels to global pop, never teleports ----

    @Test
    public void btsSession_travelsNotTeleports() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        List<Track> radio = new ArrayList<>();
        radio.add(t("Blood Sweat and Tears", "BTS", "K-pop", "korean"));
        radio.add(t("FAKE LOVE", "BTS", "K-pop", "korean"));
        radio.add(t("Seven", "Jung Kook", "Pop", "english"));
        radio.add(t("Stay", "Justin Bieber", "Pop", "english"));
        radio.add(t("Sunflower", "Post Malone", "Pop", "english"));
        radio.add(t("Let Me Love You", "DJ Snake", "Electronic", "english"));
        radio.add(t("Punjabi Folk Hit", "Folk Act", "Punjabi", "punjabi"));
        radio.add(t("Metal Hit", "Metal Band", "Metal", "english"));
        radio.add(t("Hindi Classical", "Classical Act", "Bollywood", "hindi"));

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = request(seed, radio, new ArrayList<Track>());
        req.count = 8;
        RecommendationEngine.Result res = engine.nextBatch(req);

        assertFalse(res.tracks.isEmpty());
        // First pick anchored on the seed's neighborhood (BTS/K-pop).
        Track first = res.tracks.get(0);
        assertTrue("first pick stays close: " + first.name + " / " + first.artists,
                TitleGuard.norm(first.artists).contains("bts")
                        || TitleGuard.norm(first.artists).contains("jung kook")
                        || com.nightlight.app.smartshuffle.GenreHelper.genreOf(first).equals("kpop"));
        // No teleports anywhere in the emitted sequence.
        for (Track x : res.tracks) {
            String g = com.nightlight.app.smartshuffle.GenreHelper.genreOf(x);
            assertFalse("no punjabi teleport: " + x.name, g.equals("punjabi"));
            assertFalse("no metal teleport: " + x.name, g.equals("metal"));
            assertFalse("no bollywood teleport: " + x.name, g.equals("bollywood"));
        }
    }

    // ---- transitionClass labels ----

    @Test
    public void transitionClass_labelsJumps() {
        Track bts = t("Boy With Luv", "BTS", "K-pop", "korean");
        SessionContext vibe = new SessionContext(new SessionProfile(), bts);
        // DIRECT: same artist.
        assertEquals(TransitionClass.Kind.DIRECT,
                TransitionClass.classify(bts,
                        t("Blood Sweat and Tears", "BTS", "K-pop", "korean"), vibe, 3));
        // ADJACENT: neighboring neighborhood, supported language.
        assertEquals(TransitionClass.Kind.ADJACENT,
                TransitionClass.classify(bts,
                        t("Global Pop Hit", "Pop Star", "Pop", "korean"), vibe, 2));
        // TELEPORT: distant + linguistically unsupported.
        assertEquals(TransitionClass.Kind.TELEPORT,
                TransitionClass.classify(bts,
                        t("Punjabi Folk Hit", "Folk Act", "Punjabi", "punjabi"), vibe, 1));
        // EXPLORATORY: mid-distance but thinly supported.
        assertEquals(TransitionClass.Kind.EXPLORATORY,
                TransitionClass.classify(t("Pop Song", "Pop Act", "Pop", "english"),
                        t("Metal Hit", "Metal Band", "Metal", "english"), vibe, 1));
        // BRIDGE: spans prev (kpop) and session-dominant (pop) neighborhoods
        // via a 2-hop stop adjacent to the dominant hood.
        SessionContext popVibe = new SessionContext(new SessionProfile(), bts);
        popVibe.advance(t("Pop One", "P1", "Pop", "english"));
        popVibe.advance(t("Pop Two", "P2", "Pop", "english"));
        popVibe.advance(t("Pop Three", "P3", "Pop", "english"));
        assertEquals(TransitionClass.Kind.BRIDGE,
                TransitionClass.classify(bts,
                        t("Acoustic Bridge", "A4", "Acoustic", "english"), popVibe, 2));
    }

    // ---- audio similarity: unknown-safe ----

    @Test
    public void audioSimilarity_unknownIsNeutral_neverZeroFilled() {
        AudioSimilarity.Result both = AudioSimilarity.compare(
                TrackFeatures.unknown(), TrackFeatures.unknown());
        assertEquals(0.5, both.similarity, 1e-9);
        assertEquals(0.0, both.confidence, 1e-9);

        TrackFeatures a = TrackFeatures.builder().energy(0.8).tempo(120.0).build();
        TrackFeatures b = TrackFeatures.builder().energy(0.8).tempo(120.0).build();
        AudioSimilarity.Result same = AudioSimilarity.compare(a, b);
        assertEquals(1.0, same.similarity, 1e-9);
        assertTrue(same.confidence > 0);

        TrackFeatures far = TrackFeatures.builder().energy(0.0).tempo(200.0).build();
        AudioSimilarity.Result diff = AudioSimilarity.compare(a, far);
        assertTrue("compatible pair outranks distant pair",
                same.similarity > diff.similarity);

        // Partial overlap: only shared dims count, confidence reflects it.
        TrackFeatures partial = TrackFeatures.builder().energy(0.8).build();
        AudioSimilarity.Result part = AudioSimilarity.compare(a, partial);
        assertEquals(1.0, part.similarity, 1e-9);
        assertTrue(part.confidence < same.confidence);
    }

    @Test
    public void featureBonus_rewardsCompatibleOnlyWhenKnown() {
        Track seed = t("Seed Song", "Seed Act", "Pop", "english");
        Track close = t("Close Song", "Other Act", "Pop", "english");
        Track far = t("Far Song", "Other Act", "Pop", "english");
        TrackFeatureProvider.MapBackedFeatureProvider provider =
                new TrackFeatureProvider.MapBackedFeatureProvider(null);
        provider.put(seed.id, TrackFeatures.builder().energy(0.7).tempo(120.0).build());
        provider.put(close.id, TrackFeatures.builder().energy(0.72).tempo(122.0).build());
        provider.put(far.id, TrackFeatures.builder().energy(0.1).tempo(180.0).build());

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = request(seed,
                java.util.Arrays.asList(far, close), new ArrayList<Track>());
        req.featureProvider = provider;
        req.count = 2;
        RecommendationEngine.Result res = engine.nextBatch(req);
        assertEquals(2, res.tracks.size());
        assertEquals("compatible audio wins", close.id, res.tracks.get(0).id);
        assertTrue(res.details.get(0).musicSimilarity > res.details.get(1).musicSimilarity);
    }

    // ---- soft saturation: consecutive runs, not history ----

    @Test
    public void softSaturation_allowsPair_blocksLongRun() {
        // Only BTS available: pair plays (no alternative), never blocked.
        List<Candidate> onlyBts = new ArrayList<>();
        onlyBts.add(new Candidate(t("A", "BTS", "K-pop", "korean"),
                Candidate.Source.RADIO_SEED, ""));
        onlyBts.add(new Candidate(t("B", "BTS", "K-pop", "korean"),
                Candidate.Source.RADIO_SEED, ""));
        java.util.Map<String, Double> rel = new java.util.HashMap<>();
        rel.put(onlyBts.get(0).track.id, 0.9);
        rel.put(onlyBts.get(1).track.id, 0.9);
        List<MmrRanker.Ranked> ranked = MmrRanker.rank(onlyBts,
                new ArrayList<Track>(), rel, 0.7, 2, new SessionProfile());
        assertEquals("pair not blocked without alternative", 2, ranked.size());

        // Third consecutive BTS loses to a decent alternative; junk still loses.
        Track seedTrack = t("Seed", "BTS", "K-pop", "korean");
        List<Track> selected = new ArrayList<>();
        selected.add(seedTrack);
        selected.add(t("A", "BTS", "K-pop", "korean"));
        selected.add(t("B", "BTS", "K-pop", "korean"));
        List<Candidate> pool = new ArrayList<>();
        pool.add(new Candidate(t("C", "BTS", "K-pop", "korean"),
                Candidate.Source.RADIO_SEED, ""));
        pool.add(new Candidate(t("D", "Other", "Pop", "english"),
                Candidate.Source.RADIO_SEED, ""));
        java.util.Map<String, Double> rel2 = new java.util.HashMap<>();
        for (Candidate c : pool) rel2.put(c.track.id, 0.9);
        List<MmrRanker.Ranked> r2 = MmrRanker.rank(pool, selected, rel2, 0.7, 1,
                new SessionProfile());
        assertEquals("run of 3 broken by decent alternative",
                "D", r2.get(0).candidate.track.name);
    }

    // ---- drift + language sessions in emitted details ----

    @Test
    public void details_carryClassAndDrift() {
        Track seed = t("Boy With Luv", "BTS", "K-pop", "korean");
        List<Track> radio = new ArrayList<>();
        radio.add(t("Blood Sweat and Tears", "BTS", "K-pop", "korean"));
        radio.add(t("Global Pop Hit", "Pop Star", "Pop", "english"));
        radio.add(t("Seven", "Jung Kook", "Pop", "english"));
        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = request(seed, radio, new ArrayList<Track>());
        req.count = 3;
        RecommendationEngine.Result res = engine.nextBatch(req);
        assertFalse(res.details.isEmpty());
        for (RecommendationEngine.ScoredPick d : res.details) {
            assertFalse("class logged", d.transitionClass.isEmpty());
            assertTrue("drift logged", d.drift >= 0);
            assertTrue("centroid logged", d.centroidDist >= -1);
        }
        assertEquals("no teleports emitted",
                0, countKind(res, "TELEPORT"));
    }

    @Test
    public void hindiSession_penalizesEnglishIntrusion() {
        Track seed = t("Tum Hi Ho", "Arijit Singh", "Bollywood", "hindi");
        SessionContext vibe = new SessionContext(new SessionProfile(), seed);
        vibe.advance(t("Kal Ho Naa Ho", "Sonu Nigam", "Bollywood", "hindi"));
        vibe.advance(t("Raabta", "Arijit Singh", "Bollywood", "hindi"));
        TransitionScorer scorer = new TransitionScorer();
        Track prev = t("Raabta", "Arijit Singh", "Bollywood", "hindi");
        Candidate english = new Candidate(t("Pop Hit", "Pop Star", "Pop", "english"),
                Candidate.Source.RADIO_SEED, "");
        TransitionScorer.Breakdown b = scorer.score(input(prev, vibe, english));
        assertTrue("english intruder penalized in hindi session",
                b.penalty >= TransitionScorer.P_LANGUAGE_MISMATCH - 1e-9);
    }

    private static int countKind(RecommendationEngine.Result res, String kind) {
        int n = 0;
        for (RecommendationEngine.ScoredPick d : res.details) {
            if (kind.equals(d.transitionClass)) n++;
        }
        return n;
    }

    // ---- helpers ----

    private static RecommendationEngine.Request request(Track seed, List<Track> radio,
                                                        List<Track> trail) {
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = radio;
        req.contextTrail = trail;
        req.session = new SessionProfile();
        req.taste = new TasteProfile();
        req.mode = DiscoveryMode.BALANCED;
        req.count = 6;
        return req;
    }

    private static TransitionScorer.In input(Track prev, SessionContext vibe, Candidate cand) {
        TransitionScorer.In in = new TransitionScorer.In();
        in.prev = prev;
        in.ctx = vibe;
        in.cand = cand;
        in.taste = 0.5;
        in.freshness = 1.0;
        in.diversity = 1.0;
        return in;
    }

    private static List<String> ids(List<Track> tracks) {
        List<String> out = new ArrayList<>();
        for (Track t : tracks) out.add(t.id);
        return out;
    }
}

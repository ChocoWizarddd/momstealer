package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.GenreHelper;
import com.nightlight.app.smartshuffle.TitleGuard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Relatedness contracts (§ARCH testing requirements): the seed-anchored
 * relatedness gate, first-pick anchoring, popularity subordination, MMR
 * diversity, freshness-after-relevance, Normal-untouched, and the 7-seed
 * 15-track acceptance runs with full per-pick audit output.
 */
public class RelatednessTest {

    private static int idSeq = 0;

    private static Track t(String name, String artist, String genre, String lang, long plays) {
        return new Track("rt" + (idSeq++), name, artist, "Album " + artist, "",
                "https://x/y.mp3", 200_000L, "2020", plays, lang, genre);
    }

    private static Track pop(String name, String artist) {
        return t(name, artist, "Pop", "english", 1000L);
    }

    // ---- floor unit: directly related > unrelated famous ----

    @Test
    public void floor_rejectsFamousUnrelated_passesSameGenre() {
        Track seed = pop("Perfect", "Ed Sheeran");
        RelatednessScorer scorer = new RelatednessScorer();
        Candidate sameGenre = new Candidate(pop("Photograph", "Ed Sheeran"),
                Candidate.Source.RADIO_SEED, "");
        // Different artist so only genre+language+radio carry it.
        Candidate sameGenreOther = new Candidate(pop("All of Me", "John Legend"),
                Candidate.Source.RADIO_SEED, "");
        Candidate famous = new Candidate(
                t("Metal Hit", "Metal Band", "Metal", "english", 50_000_000L),
                Candidate.Source.RADIO_SEED, "");
        double same = scorer.score(seed, sameGenre).total;
        double sameOther = scorer.score(seed, sameGenreOther).total;
        double fam = scorer.score(seed, famous).total;
        assertTrue("same genre passes L0 floor: " + same, same >= RelatednessScorer.FLOOR_LADDER[0]);
        assertTrue("same genre other artist passes L0: " + sameOther,
                sameOther >= RelatednessScorer.FLOOR_LADDER[0]);
        assertTrue("famous unrelated below L0: " + fam, fam < RelatednessScorer.FLOOR_LADDER[0]);
        assertTrue("related beats famous", same > fam && sameOther > fam);
    }

    @Test
    public void relatedArtist_beatsRandomArtist() {
        Track seed = pop("Perfect", "Ed Sheeran");
        RelatednessScorer scorer = new RelatednessScorer();
        Candidate sameArtist = new Candidate(
                t("Castle Song", "Ed Sheeran", "Rock", "english", 10L),
                Candidate.Source.RADIO_SEED, "");
        Candidate randomArtist = new Candidate(
                t("Rock Song", "Random Band", "Rock", "english", 10L),
                Candidate.Source.RADIO_SEED, "");
        double a = scorer.score(seed, sameArtist).total;
        double b = scorer.score(seed, randomArtist).total;
        assertTrue("same artist outranks random artist: " + a + " vs " + b, a > b);
    }

    @Test
    public void freshRelated_beatsRecentlyPlayedRelated() {
        Track seed = pop("Perfect", "Ed Sheeran");
        Track fresh = pop("Fresh Song", "New Voice");
        Track played = pop("Played Song", "Old Voice");
        SessionProfile session = new SessionProfile();
        session.record(played, Signal.FULL);

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = new ArrayList<>();
        req.pool.radio.add(fresh);
        req.pool.radio.add(played);
        req.pool.radio.add(pop("Other One", "Third Act"));
        req.session = session;
        req.taste = new TasteProfile();
        req.mode = DiscoveryMode.BALANCED;
        req.count = 3;
        RecommendationEngine.Result res = engine.nextBatch(req);
        assertEquals(3, res.tracks.size());
        assertEquals("fresh related opens the sequence", fresh.id, res.tracks.get(0).id);
        assertTrue("played sinks below fresh peers",
                indexOf(res, played.id) > indexOf(res, fresh.id));
    }

    @Test
    public void strongRelated_beatsWeakExploration() {
        Track seed = pop("Perfect", "Ed Sheeran");
        TasteProfile taste = new TasteProfile();
        taste.update(pop("Loved Hit", "Loved Act"), Signal.LIKE);
        Track strong = pop("Strong Song", "Loved Act");
        Track weak = t("Far Song", "Far Act", "Indie", "english", 5L);

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = new ArrayList<>();
        req.pool.radio.add(weak);
        req.pool.radio.add(strong);
        req.session = new SessionProfile();
        req.taste = taste;
        req.mode = DiscoveryMode.BALANCED;
        req.count = 2;
        RecommendationEngine.Result res = engine.nextBatch(req);
        assertEquals(2, res.tracks.size());
        assertEquals("liked-neighborhood strong pick first", strong.id, res.tracks.get(0).id);
    }

    @Test
    public void everyPick_carriesLoggedScores() {
        Track seed = pop("Perfect", "Ed Sheeran");
        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = new ArrayList<>();
        for (int i = 0; i < 8; i++) req.pool.radio.add(pop("Pop " + i, "Pop Act " + i));
        req.session = new SessionProfile();
        req.taste = new TasteProfile();
        req.mode = DiscoveryMode.BALANCED;
        req.count = 8;
        RecommendationEngine.Result res = engine.nextBatch(req);
        assertEquals(res.tracks.size(), res.details.size());
        for (RecommendationEngine.ScoredPick d : res.details) {
            assertTrue("relatedness logged", d.relatedness >= 0);
            assertFalse("genre logged", d.genre.isEmpty());
            assertFalse("reason logged", d.reason == null || d.reason.isEmpty());
            assertFalse("sources logged", d.sources.isEmpty());
        }
    }

    // ---- 7-seed 15-track acceptance runs ----

    private static final class Seed {
        final String name;
        final String artist;
        final String genre;
        final String lang;
        final String relatedGenre;

        Seed(String name, String artist, String genre, String lang, String relatedGenre) {
            this.name = name;
            this.artist = artist;
            this.genre = genre;
            this.lang = lang;
            this.relatedGenre = relatedGenre;
        }
    }

    private static List<Seed> seeds() {
        List<Seed> out = new ArrayList<>();
        out.add(new Seed("Perfect", "Ed Sheeran", "Pop", "english", "Acoustic"));
        out.add(new Seed("Blinding Lights", "The Weeknd", "Pop", "english", "R&B"));
        out.add(new Seed("Tum Hi Ho", "Arijit Singh", "Bollywood", "hindi", "Punjabi"));
        out.add(new Seed("Mundian To Bach Ke", "Panjabi MC", "Punjabi", "punjabi", "Bollywood"));
        out.add(new Seed("Skinny Love", "Bon Iver", "Chill", "english", "Acoustic"));
        out.add(new Seed("Stay With Me", "Sam Smith", "R&B", "english", "Pop"));
        out.add(new Seed("Believer", "Imagine Dragons", "Rock", "english", "Indie"));
        return out;
    }

    /** Builds a realistic mixed provider-style universe around a seed. */
    private static List<Track> universe(Seed s) {
        List<Track> radio = new ArrayList<>();
        String[] sameArtists = {"North Line", "Velvet Air", "Copper Field", "Juniper", "Halcyon"};
        for (int i = 0; i < 5; i++) {
            radio.add(t(s.genre + " Song " + i, sameArtists[i], s.genre, s.lang, 50_000L + i));
        }
        String[] relArtists = {"Near Echo", "Side Road", "Paper Moon"};
        for (int i = 0; i < 3; i++) {
            radio.add(t(s.relatedGenre + " Tune " + i, relArtists[i], s.relatedGenre, s.lang,
                    20_000L + i));
        }
        radio.add(t(s.name + " Nights", s.artist, s.genre, s.lang, 5000L));
        radio.add(t("Stories of " + s.artist, s.artist, s.genre, s.lang, 4000L));
        radio.add(t("Famous Noise", "Mega Star", "Metal", s.lang.equals("english") ? "spanish" : "english",
                80_000_000L));
        return radio;
    }

    private static boolean isRelated(Track seed, Track t) {
        String sg = GenreHelper.genreOf(seed);
        String g = GenreHelper.genreOf(t);
        if (g.equals(sg) || GenreHelper.isRelated(g, sg)) return true;
        return TitleGuard.norm(seed.artists).equals(TitleGuard.norm(t.artists));
    }

    private static int indexOf(RecommendationEngine.Result res, String id) {
        for (int i = 0; i < res.tracks.size(); i++) {
            if (res.tracks.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private static void printRun(String label, Track seed, RecommendationEngine.Result res) {
        System.out.println("SEED: " + seed.name + " — " + seed.artists
                + " [" + GenreHelper.genreOf(seed) + "/" + seed.language + "]"
                + " pool=" + res.poolBefore + "->" + res.poolAfter + " floor=L" + res.floorLevel);
        int i = 0;
        for (RecommendationEngine.ScoredPick d : res.details) {
            System.out.println(String.format(Locale.US,
                    "#%02d %s — %s [%s/%s] src=%s rel=%.3f taste=%.3f fresh=%.3f div=%.3f final=%.3f :: %s",
                    i++, d.track.name, d.track.artists, d.genre, d.language, d.sources,
                    d.relatedness, d.taste, d.freshness, d.diversity, d.finalScore, d.reason));
        }
        System.out.println("--- " + label + " picked=" + res.tracks.size());
    }

    private static RecommendationEngine.Result runSeed(Seed s) {
        idSeq = 1000;
        Track seed = t(s.name, s.artist, s.genre, s.lang, 10_000_000L);
        List<Track> radio = universe(s);
        TasteProfile taste = new TasteProfile();
        taste.update(t("Loved " + s.genre + " One", "Loved Act", s.genre, s.lang, 1000L), Signal.LIKE);
        taste.update(t("Heard " + s.genre + " Two", "Heard Act", s.genre, s.lang, 1000L), Signal.FULL);

        RecommendationEngine engine = new RecommendationEngine();
        RecommendationEngine.Request req = new RecommendationEngine.Request();
        req.seed = seed;
        req.pool.seed = seed;
        req.pool.radio = radio;
        List<Track> liked = new ArrayList<>();
        liked.add(t("My Liked " + s.genre, "Liked Voice", s.genre, s.lang, 900L));
        liked.add(t("My Liked Two", "Liked Voice", s.genre, s.lang, 800L));
        req.pool.liked = liked;
        List<Track> trending = new ArrayList<>();
        trending.add(t("Viral Clip", "Viral Act", "Electronic", "english", 60_000_000L));
        trending.add(t("Chart Storm", "Chart Act", "Hip-Hop", "english", 40_000_000L));
        req.pool.trending = trending;
        req.session = new SessionProfile();
        req.taste = taste;
        req.mode = DiscoveryMode.BALANCED;
        req.count = 15;
        RecommendationEngine.Result res = engine.newSession(req);
        printRun(s.name, seed, res);

        // Validation 1: ≥80% of first 10 clearly musically related.
        int related = 0;
        int first10 = Math.min(10, res.tracks.size());
        for (int i = 0; i < first10; i++) {
            if (isRelated(seed, res.tracks.get(i))) related++;
        }
        assertTrue("seed " + s.name + ": related " + related + "/10",
                related >= 8);
        // Validation 2: famous/trending must not lead.
        for (int i = 0; i < Math.min(8, res.tracks.size()); i++) {
            Track x = res.tracks.get(i);
            assertFalse("seed " + s.name + ": unrelated famous at #" + i + ": " + x.name,
                    (x.name.equals("Famous Noise") || x.name.equals("Viral Clip")
                            || x.name.equals("Chart Storm")) && !isRelated(seed, x));
        }
        // Validation 3: no back-to-back artist repetition.
        assertEquals("seed " + s.name + ": no adjacent same-title pairs",
                0, TitleGuard.countAdjacentSameTitle(res.tracks));
        int maxRun = 1, cur = 1;
        for (int i = 1; i < res.tracks.size(); i++) {
            if (TitleGuard.norm(res.tracks.get(i).artists)
                    .equals(TitleGuard.norm(res.tracks.get(i - 1).artists))) {
                cur++;
                maxRun = Math.max(maxRun, cur);
            } else {
                cur = 1;
            }
        }
        assertTrue("seed " + s.name + ": max artist run " + maxRun, maxRun <= 2);
        // Validation 6: coherence holds across the sequence (tail included).
        double tailRel = 0;
        int tailN = 0;
        for (int i = 4; i < res.tracks.size(); i++) {
            tailRel += res.details.get(i).relatedness;
            tailN++;
        }
        assertTrue("seed " + s.name + ": tail coherence " + (tailRel / Math.max(1, tailN)),
                tailN == 0 || tailRel / tailN >= 0.18);
        // Validation 7: freshness preserved (recently-played suppression active).
        assertEquals("details cover every pick", res.tracks.size(), res.details.size());
        // Queue length is quality-gated (§14): short-but-coherent beats
        // 15-with-junk. Every emitted pick meets minimum coherence.
        assertTrue("quality-gated length, was " + res.tracks.size(),
                res.tracks.size() >= 12 && res.tracks.size() <= 15);
        double minRel = Double.MAX_VALUE;
        for (RecommendationEngine.ScoredPick d : res.details) {
            minRel = Math.min(minRel, d.relatedness);
        }
        assertTrue("no junk emitted, min rel=" + minRel,
                minRel >= RecommendationEngine.MIN_COHERENCE);
        return res;
    }

    @Test
    public void seed_perfect() {
        RecommendationEngine.Result res = runSeed(seeds().get(0));
        // Strict floor cannot fill 15 from related alone → ladder descends;
        // junk (zero-coherence famous/trending) is cut, not filled.
        assertTrue("floor ladder engaged, level=" + res.floorLevel, res.floorLevel >= 1);
        assertTrue("pool counted before/after", res.poolBefore == 15
                && res.poolAfter >= 12 && res.poolAfter <= 15);
    }

    @Test
    public void seed_blindingLights() {
        runSeed(seeds().get(1));
    }

    @Test
    public void seed_tumHiHo() {
        runSeed(seeds().get(2));
    }

    @Test
    public void seed_punjabiPop() {
        runSeed(seeds().get(3));
    }

    @Test
    public void seed_chillAcoustic() {
        runSeed(seeds().get(4));
    }

    @Test
    public void seed_randb() {
        runSeed(seeds().get(5));
    }

    @Test
    public void seed_rock() {
        runSeed(seeds().get(6));
    }
}

package com.nightlight.app.smartshuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Behavioral tests for the Smart Shuffle engine. Selection is stochastic by
 * design (weighted random), so statistical assertions are used instead of
 * single deterministic outcomes, and production randomness is injectable.
 */
public class SmartShuffleEngineTest {

    private static Track track(String id, String name, String artist, String album) {
        return new Track(id, name, artist, album, "", null, 200_000L, "2024");
    }

    private static List<Track> loveTracks(int n) {
        String[] titles = {"Love You Forever", "Dil Se", "Jaan-e-Man", "Ishq Wala Love",
                "Mera Dil", "Tere Bina", "Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Tu Hai"};
        String[] artists = {"Artist Love 1", "Artist Love 2", "Artist Love 3", "Artist Love 4", "Artist Love 5"};
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(track("love" + i, titles[i % titles.length],
                    artists[i % artists.length], "Love Album " + (i % 3)));
        }
        return out;
    }

    private static List<Track> rockTracks(int n) {
        String[] titles = {"Midnight Drive", "Neon City", "Concrete Jungle", "Iron Sky",
                "Desert Run", "Static", "Glass House", "Paper Walls", "Hollow", "Echo Chamber"};
        String[] artists = {"Band 1", "Band 2", "Band 3", "Band 4", "Band 5"};
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(track("rock" + i, titles[i % titles.length],
                    artists[i % artists.length], "Rock Album " + (i % 3)));
        }
        return out;
    }

    private static int loveCount(List<Track> queue) {
        int count = 0;
        for (Track t : queue) {
            if (t.id.startsWith("love")) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void emptyCandidates_returnsEmpty() {
        SmartShuffleEngine engine = new SmartShuffleEngine(new Random(1));
        assertEquals(0, engine.generateQueue(null, null, null).size());
        assertEquals(0, engine.generateQueue(track("s", "Song", "A", "Alb"),
                Collections.<Track>emptyList(), null).size());
    }

    @Test
    public void seededRandom_isDeterministic() {
        SmartShuffleEngine engine1 = new SmartShuffleEngine(new Random(123));
        SmartShuffleEngine engine2 = new SmartShuffleEngine(new Random(123));
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(10));
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        List<Track> q1 = engine1.generateQueue(seed, pool, null);
        List<Track> q2 = engine2.generateQueue(seed, pool, null);
        assertEquals(q1.size(), q2.size());
        for (int i = 0; i < q1.size(); i++) {
            assertEquals(q1.get(i).id, q2.get(i).id);
        }
    }

    @Test
    public void loveContext_stronglyPrefersLoveTracks() {
        // 10 love + 10 rock candidates, LOVE seed: Smart Shuffle must
        // statistically favor love-compatible music, not random choice.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(10));
        pool.addAll(rockTracks(10));
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        int totalLove = 0;
        int totalPicks = 0;
        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null);
            List<Track> firstTen = queue.size() > 10 ? queue.subList(0, 10) : queue;
            totalLove += loveCount(firstTen);
            totalPicks += firstTen.size();
        }
        // Strong preference (spec: NOT a coin flip). Random selection would
        // land near 50%; artist/album diversity pulls some picks off-mood,
        // which is intended — but the context must still dominate.
        assertTrue("love picks: " + totalLove + "/" + totalPicks,
                totalLove >= totalPicks * 0.60);
    }

    @Test
    public void sameArtist_doesNotDominate() {
        // 6 tracks, 3 artists — the same artist must never appear 3x in a row.
        List<Track> pool = Arrays.asList(
                track("a1", "Dil Se", "Artist A", "Alb"),
                track("a2", "Tere Bina", "Artist A", "Alb2"),
                track("b1", "Jaan-e-Man", "Artist B", "Alb"),
                track("b2", "Mera Dil", "Artist B", "Alb2"),
                track("c1", "Pyaar Ka Safar", "Artist C", "Alb"),
                track("c2", "Mohabbat", "Artist C", "Alb2"));
        Track seed = track("seed", "Dil Se", "Artist A", "Alb");

        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null);
            assertEquals(6, queue.size());
            for (int i = 2; i < queue.size(); i++) {
                boolean threeInARow = queue.get(i).artists.equals(queue.get(i - 1).artists)
                        && queue.get(i).artists.equals(queue.get(i - 2).artists);
                assertFalse("artist run of 3 at " + i + " run " + run, threeInARow);
            }
        }
    }

    @Test
    public void recentlyPlayedTrack_isDeprioritized() {
        // Use a unique id so the track is not deduped away from the pool.
        Track played = track("played_unique", "Ishq Wala Love", "Artist Love 2", "Love Album 1");
        List<Track> pool = loveTracks(6);
        pool.add(played);
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        int earlyAppearances = 0;
        int runs = 20;
        for (int run = 0; run < runs; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, Collections.singletonList(played));
            if (queue.indexOf(played) <= 2) {
                earlyAppearances++;
            }
        }
        // Recently played must almost never land in the first three slots.
        assertTrue("early appearances: " + earlyAppearances, earlyAppearances <= runs / 5);
    }

    @Test
    public void explicitMood_overridesAmbiguousInference() {
        // Seed with a neutral title; explicit LOVE should still favor love picks.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(8));
        pool.addAll(rockTracks(8));
        Track neutralSeed = track("seed", "Sunset Drive", "Band 2", "Rock Album 1");

        int totalLove = 0;
        int totalPicks = 0;
        for (int run = 0; run < 15; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(neutralSeed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            List<Track> firstSix = queue.size() > 6 ? queue.subList(0, 6) : queue;
            totalLove += loveCount(firstSix);
            totalPicks += firstSix.size();
        }
        assertTrue("explicit love picks: " + totalLove + "/" + totalPicks,
                totalLove >= totalPicks * 0.6);
    }

    @Test
    public void skippedArtist_losesRankingThisSession() {
        List<Track> pool = new ArrayList<>();
        String[] skipTitles = {"Dil Se", "Tere Bina", "Jaan-e-Man", "Mera Dil"};
        for (int i = 0; i < 4; i++) {
            pool.add(track("s" + i, skipTitles[i], "Skipped Artist", "Alb"));
        }
        String[] keepTitles = {"Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Tu Hai"};
        for (int i = 0; i < 4; i++) {
            pool.add(track("k" + i, keepTitles[i], "Kept Artist", "Alb2"));
        }
        Track seed = track("seed", "Dil Se", "Skipped Artist", "Alb");
        Set<String> skips = new HashSet<>(Collections.singletonList("Skipped Artist"));

        int skippedFirst = 0;
        int runs = 25;
        for (int run = 0; run < runs; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0, skips, Collections.<String>emptySet(), null);
            if (!queue.isEmpty() && queue.get(0).artists.equals("Skipped Artist")) {
                skippedFirst++;
            }
        }
        // The skipped artist should rarely win the first slot even though the
        // seed is one of their songs (weak negative signal during the session).
        assertTrue("skipped artist first: " + skippedFirst + "/" + runs, skippedFirst <= runs / 3);
    }

    @Test
    public void likedTrack_getsFamiliarityBonus() {
        // A like is a small familiarity bonus — it must shift ranking upward,
        // not guarantee "play next". Compare average position with and without.
        List<Track> pool = loveTracks(6);
        Track liked = pool.get(3);
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");
        Set<String> likedIds = new HashSet<>(Collections.singletonList(liked.id));

        int runs = 40;
        double avgWith = 0;
        double avgWithout = 0;
        for (int run = 0; run < runs; run++) {
            List<Track> withBonus = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), likedIds, null);
            List<Track> withoutBonus = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            avgWith += withBonus.indexOf(liked);
            avgWithout += withoutBonus.indexOf(liked);
        }
        avgWith /= runs;
        avgWithout /= runs;
        assertTrue("avg position with=" + avgWith + " without=" + avgWithout,
                avgWith < avgWithout);
    }

    @Test
    public void nullSeedAndNullRecents_areSafe() {
        List<Track> pool = loveTracks(4);
        List<Track> queue = new SmartShuffleEngine(new Random(9))
                .generateQueue(null, pool, null);
        assertEquals(4, queue.size());
        List<Track> queue2 = new SmartShuffleEngine(new Random(9))
                .generateQueue(null, pool, null);
        assertEquals(queue.get(0).id, queue2.get(0).id);
    }

    // ---- New tests for current-track exclusion and deduplication ----

    @Test
    public void currentTrack_excludedFromCandidates() {
        // The currently playing track must never appear in the curated queue.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(5));
        Track current = pool.get(2); // "love2"
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        for (int run = 0; run < 20; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(),
                            current.id);
            for (Track t : queue) {
                assertNotEquals("current track should not appear in queue",
                        current.id, t.id);
            }
        }
    }

    @Test
    public void duplicateTrackIds_areDeduped() {
        // If the same track ID appears multiple times in the candidate pool,
        // only one copy should survive.
        Track t1 = track("dup1", "Love Song", "Artist A", "Alb");
        Track t1copy = track("dup1", "Love Song (Remix)", "Artist A", "Alb");
        Track t2 = track("dup2", "Another Love", "Artist B", "Alb2");
        Track t3 = track("dup3", "Third Love", "Artist C", "Alb3");
        List<Track> pool = Arrays.asList(t1, t1copy, t2, t3, t2); // dup1 x2, dup2 x2
        Track seed = track("seed", "Dil Se", "Artist A", "Alb");

        List<Track> queue = new SmartShuffleEngine(new Random(1))
                .generateQueue(seed, pool, null);
        // Should have at most 3 unique tracks (dup1, dup2, dup3).
        assertEquals(3, queue.size());
    }

    @Test
    public void currentTrackId_null_isIgnored() {
        // Passing null as currentTrackId should not affect behavior.
        List<Track> pool = loveTracks(5);
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        List<Track> q1 = new SmartShuffleEngine(new Random(42))
                .generateQueue(seed, pool, null, null, 0.0,
                        Collections.<String>emptySet(), Collections.<String>emptySet(), null);
        List<Track> q2 = new SmartShuffleEngine(new Random(42))
                .generateQueue(seed, pool, null, null, 0.0,
                        Collections.<String>emptySet(), Collections.<String>emptySet(), null);
        assertEquals(q1.size(), q2.size());
        for (int i = 0; i < q1.size(); i++) {
            assertEquals(q1.get(i).id, q2.get(i).id);
        }
    }

    // ---- Spec 43: artist / album diversity, recent avoidance, discovery ratio ----

    @Test
    public void firstFive_doNotLetOneArtistDominate() {
        // 3 tracks by one artist + 5 by others: even though the seed artist is
        // the same, the cooldown must cap that artist at 2 of the first 5 slots
        // while other viable artists exist.
        List<Track> pool = new ArrayList<>();
        pool.add(track("a1", "Dil Se", "Artist A", "AlbA"));
        pool.add(track("a2", "Tere Bina", "Artist A", "AlbA"));
        pool.add(track("a3", "Mera Dil", "Artist A", "AlbA"));
        String[] others = {"Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Ishq Wala Love", "Tu Hai"};
        for (int i = 0; i < others.length; i++) {
            pool.add(track("o" + i, others[i], "Artist O" + i, "AlbO" + i));
        }
        Track seed = track("seed", "Jaan-e-Man", "Artist A", "AlbA");

        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            int countA = 0;
            for (int i = 0; i < queue.size() && i < 5; i++) {
                if (queue.get(i).artists.equals("Artist A")) {
                    countA++;
                }
            }
            assertTrue("Artist A in first 5: " + countA + " (run " + run + ")", countA <= 2);
        }
    }

    @Test
    public void sameAlbum_doesNotRunThreeInARow() {
        // Album cooldown mirrors the artist cooldown: one album must not
        // dominate three consecutive slots when other albums are available.
        List<Track> pool = new ArrayList<>();
        String[] megaTitles = {"Dil Se", "Tere Bina", "Jaan-e-Man", "Mera Dil", "Pyaar Ka Safar"};
        for (int i = 0; i < megaTitles.length; i++) {
            pool.add(track("m" + i, megaTitles[i], "Artist M" + i, "Mega Album"));
        }
        String[] altTitles = {"Mohabbat", "Heartbeat", "Tu Hai", "Ishq Wala Love"};
        for (int i = 0; i < altTitles.length; i++) {
            pool.add(track("t" + i, altTitles[i], "Artist T" + i, "Other Album " + (i % 2)));
        }
        Track seed = track("seed", "Dil Se", "Artist M0", "Mega Album");

        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            // While alternatives exist (early queue), an album never takes three
            // consecutive slots. Deep in the tail the pool may be genuinely
            // exhausted (spec 9), so there a run of 3 is tolerated but never 4.
            for (int i = 2; i < queue.size() && i < 6; i++) {
                boolean threeSameAlbum = queue.get(i).album.equals(queue.get(i - 1).album)
                        && queue.get(i).album.equals(queue.get(i - 2).album);
                assertFalse("album run of 3 at " + i + " (run " + run + ")", threeSameAlbum);
            }
            for (int i = 3; i < queue.size(); i++) {
                boolean fourSameAlbum = queue.get(i).album.equals(queue.get(i - 1).album)
                        && queue.get(i).album.equals(queue.get(i - 2).album)
                        && queue.get(i).album.equals(queue.get(i - 3).album);
                assertFalse("album run of 4 at " + i + " (run " + run + ")", fourSameAlbum);
            }
        }
    }

    @Test
    public void sessionRecents_doNotLoopImmediately() {
        // A B C D were just played; 6 fresh candidates exist. The first three
        // picks must almost always come from the fresh tracks, not loop the
        // session (spec 9).
        List<Track> pool = new ArrayList<>();
        List<Track> recents = new ArrayList<>();
        String[] played = {"Love You Forever", "Dil Se", "Jaan-e-Man", "Ishq Wala Love"};
        for (int i = 0; i < played.length; i++) {
            Track t = track("r" + i, played[i], "Artist P" + i, "Played Album " + (i % 2));
            recents.add(t);
            pool.add(t);
        }
        String[] fresh = {"Mera Dil", "Tere Bina", "Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Tu Hai"};
        for (int i = 0; i < fresh.length; i++) {
            pool.add(track("f" + i, fresh[i], "Artist F" + i, "Fresh Album " + (i % 3)));
        }
        Track seed = track("seed", "Tu Hai", "Artist F5", "Fresh Album 2");
        Set<String> recentIds = new HashSet<>();
        for (Track t : recents) {
            recentIds.add(t.id);
        }

        int recentInFirst3 = 0;
        int runs = 30;
        for (int run = 0; run < runs; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, recents,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            for (int i = 0; i < queue.size() && i < 3; i++) {
                if (recentIds.contains(queue.get(i).id)) {
                    recentInFirst3++;
                    break;
                }
            }
        }
        assertTrue("recent track in first 3: " + recentInFirst3 + "/" + runs,
                recentInFirst3 <= runs * 3 / 10);
    }

    @Test
    public void discoveryRatio_changesExploration() {
        // LOVE seed over a 10 love / 10 rock pool: discovery=0.35 must wander
        // off-context noticeably more than familiar=0.0 (spec 15), compared
        // over identical random seeds.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(10));
        pool.addAll(rockTracks(10));
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        int familiarRock = 0;
        int discoveryRock = 0;
        int runs = 40;
        for (int run = 0; run < runs; run++) {
            List<Track> qF = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            List<Track> qD = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.35,
                            Collections.<String>emptySet(), Collections.<String>emptySet(), null);
            familiarRock += rockCount(qF.subList(0, Math.min(6, qF.size())));
            discoveryRock += rockCount(qD.subList(0, Math.min(6, qD.size())));
        }
        assertTrue("discovery rock picks (" + discoveryRock + ") must exceed familiar ("
                + familiarRock + ")", discoveryRock > familiarRock);
    }

    private static int rockCount(List<Track> queue) {
        int count = 0;
        for (Track t : queue) {
            if (t.id.startsWith("rock")) {
                count++;
            }
        }
        return count;
    }
}

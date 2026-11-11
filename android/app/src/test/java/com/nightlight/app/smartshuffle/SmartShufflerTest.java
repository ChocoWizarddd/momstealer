package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;

/**
 * Unit tests for SmartShuffler — exact port of test_shuffle.dart.
 *
 * Verifies the guarantees of the system:
 *  - permutation (every song kept exactly once)
 *  - pinned opener stays first
 *  - adjacent same-artist tracks are (almost) eliminated
 *  - recency penalties push just-played songs away from the front
 *  - A-B-A-B patterns score worse than varied orders
 *  - output stays random (FY unbiasedness + non-determinism)
 *  - tiny playlists degrade gracefully
 */
public class SmartShufflerTest {

    private static Track song(String id, String artist, String album) {
        return new Track(id, id, artist, album, "", "", 0, "");
    }

    private static Track song(String id, String artist) {
        return song(id, artist, "Album");
    }

    @Test
    public void testPermutationKeepsEverySongExactlyOnce() {
        List<Track> songs = library();
        List<Track> out = new SmartShuffler(Collections.emptyList(), new Random(5)).shuffle(songs);
        Assert.assertEquals("keeps every song exactly once", songs.size(), out.size());
        Set<String> ids = new HashSet<>();
        for (Track t : out) ids.add(t.id);
        Assert.assertEquals("no duplicates", songs.size(), ids.size());
    }

    @Test
    public void testPinnedOpenerStaysFirst() {
        List<Track> songs = library();
        Track pinned = songs.get(7);
        List<Track> out = new SmartShuffler(Collections.emptyList(), new Random(1)).shuffle(songs, pinned);
        Assert.assertEquals("pinned first song stays first", pinned.id, out.get(0).id);
    }

    @Test
    public void testAvoidsAdjacentSameArtist() {
        int violations = 0;
        for (int t = 0; t < 60; t++) {
            List<Track> o = new SmartShuffler(Collections.emptyList(), new Random(t)).shuffle(library());
            for (int i = 1; i < o.size(); i++) {
                if (o.get(i).artists.equals(o.get(i - 1).artists)) {
                    violations++;
                }
            }
        }
        Assert.assertTrue("avoids adjacent same-artist (" + violations + " violations/60)", violations <= 2);
    }

    @Test
    public void testRecencyJustPlayedNeverOpens() {
        List<Track> songs = library();
        List<Track> recent = new ArrayList<>();
        recent.add(songs.get(0));
        recent.add(songs.get(5));
        recent.add(songs.get(10));

        int recentOpeners = 0;
        int recentTop3 = 0;
        for (int t = 0; t < 40; t++) {
            List<Track> o = new SmartShuffler(recent, new Random(100 + t)).shuffle(songs);
            if (o.get(0).id.equals(recent.get(0).id)) recentOpeners++;
            boolean inTop3 = false;
            for (int i = 0; i < 3 && i < o.size(); i++) {
                if (o.get(i).id.equals(recent.get(0).id)) {
                    inTop3 = true;
                    break;
                }
            }
            if (inTop3) recentTop3++;
        }
        Assert.assertEquals("just-played song never opens", 0, recentOpeners);
        Assert.assertTrue("just-played song rare in top-3 (" + recentTop3 + "/40)", recentTop3 <= 6);
    }

    @Test
    public void testScorerPenalisesABABPatterns() {
        List<Track> abab = new ArrayList<>();
        abab.add(song("1", "A"));
        abab.add(song("2", "B"));
        abab.add(song("3", "A"));
        abab.add(song("4", "B"));
        abab.add(song("5", "C"));
        abab.add(song("6", "D"));

        List<Track> varied = new ArrayList<>();
        varied.add(song("1", "A"));
        varied.add(song("2", "B"));
        varied.add(song("3", "C"));
        varied.add(song("4", "D"));
        varied.add(song("5", "E"));
        varied.add(song("6", "F"));

        SmartShuffler s = new SmartShuffler(Collections.emptyList(), new Random(1));
        double scoreABAB = s.score(abab);
        double scoreVaried = s.score(varied);
        Assert.assertTrue("penalises A-B-A-B patterns (abab=" + scoreABAB + " < varied=" + scoreVaried + ")", scoreABAB < scoreVaried);
    }

    @Test
    public void testFisherYatesUnbiased() {
        List<Track> uniform = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            uniform.add(song("s" + i, "Artist " + (i % 10)));
        }

        int[] positionCounts = new int[30];
        for (int t = 0; t < 300; t++) {
            List<Track> o = new SmartShuffler(Collections.emptyList(), new Random(1000 + t)).shuffle(uniform);
            int pos = -1;
            for (int i = 0; i < o.size(); i++) {
                if (o.get(i).id.equals("s0")) {
                    pos = i;
                    break;
                }
            }
            if (pos >= 0 && pos < 30) positionCounts[pos]++;
        }

        boolean inBand = true;
        for (int c : positionCounts) {
            if (c < 2 || c > 28) {
                inBand = false;
                break;
            }
        }
        Assert.assertTrue("Fisher-Yates positions are uniform (300 trials)", inBand);
    }

    @Test
    public void testNotDeterministic() {
        Set<String> seen = new HashSet<>();
        SmartShuffler shuffler = new SmartShuffler(Collections.emptyList(), new Random(77));
        for (int i = 0; i < 20; i++) {
            List<Track> out = shuffler.shuffle(library());
            StringBuilder sb = new StringBuilder();
            for (Track t : out) {
                if (sb.length() > 0) sb.append(",");
                sb.append(t.id);
            }
            seen.add(sb.toString());
        }
        Assert.assertTrue("stays random across calls (" + seen.size() + " variants/20)", seen.size() > 5);
    }

    @Test
    public void testTinyPlaylistDegradesGracefully() {
        List<Track> two = new ArrayList<>();
        two.add(song("p1", "A"));
        two.add(song("p2", "B"));
        List<Track> out = new SmartShuffler(Collections.emptyList(), new Random(2)).shuffle(two);
        Assert.assertEquals("tiny playlist degrades gracefully", 2, out.size());
        Set<String> ids = new HashSet<>();
        for (Track t : out) ids.add(t.id);
        Assert.assertEquals("no duplicates in tiny playlist", 2, ids.size());
    }

    @Test
    public void testSingleArtistPlaylistStillShuffles() {
        List<Track> solo = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            solo.add(song("o" + i, "OnlyOne", "Al" + i));
        }
        List<Track> out = new SmartShuffler(Collections.emptyList(), new Random(3)).shuffle(solo);
        Assert.assertEquals("single-artist playlist still shuffles", 4, out.size());
    }

    // Helper to create a standard test library
    private List<Track> library() {
        List<Track> lib = new ArrayList<>();
        for (int i = 0; i < 4; i++) lib.add(song("a" + i, "Alpha", "Al" + i));
        for (int i = 0; i < 4; i++) lib.add(song("b" + i, "Beta", "Bl" + i));
        for (int i = 0; i < 4; i++) lib.add(song("c" + i, "Gamma", "Ga" + i));
        for (int i = 0; i < 4; i++) lib.add(song("d" + i, "Delta", "De" + i));
        return lib;
    }
}
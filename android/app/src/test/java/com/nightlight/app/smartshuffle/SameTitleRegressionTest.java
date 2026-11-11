package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression: searching one song ("i thought i saw your face today") and
 * pressing NEXT played the same title 4-5 times in a row — the queue was a
 * raw wall of same-title versions/covers by different artists.
 *
 * Invariant under test: in any shuffle mode, no two adjacent queue entries
 * may share a canonical title while a different title exists to separate
 * them. The backend radio path already guarantees this; these tests pin the
 * Android side (entry decluster, Smart curation, Normal permutation).
 */
public class SameTitleRegressionTest {

    private static Track track(String id, String name, String artist) {
        return new Track(id, name, artist, "Album " + artist, "", null, 200_000L, "2024");
    }

    /** Search-wall pool: same-title recordings + enough variety to separate. */
    private static List<Track> searchWall() {
        List<Track> pool = new ArrayList<>();
        String[] artists = {"Aurora Skies", "Midnight Echo", "Cafe Strings", "Urban Choir",
                "Luna Park", "Velvet Air"};
        String[] variants = {"I Thought I Saw Your Face Today",
                "I Thought I Saw Your Face Today (Acoustic)",
                "I Thought I Saw Your Face Today - Slowed",
                "I Thought I Saw Your Face Today (Live)",
                "I Thought I Saw Your Face Today [Official Video]",
                "I Thought I Saw Your Face Today (Cover)"};
        for (int i = 0; i < variants.length; i++) {
            pool.add(track("v" + i, variants[i], artists[i]));
        }
        pool.add(track("o1", "Photograph", "Ed Sheeran"));
        pool.add(track("o2", "Shape of You", "Ed Sheeran"));
        pool.add(track("o3", "Blinding Lights", "The Weeknd"));
        pool.add(track("o4", "Kesariya", "Arijit Singh"));
        pool.add(track("o5", "Tum Hi Ho", "Arijit Singh"));
        pool.add(track("o6", "Levitating", "Dua Lipa"));
        return pool;
    }

    @Test
    public void canonicalTitle_collapsesVersionsAndCovers() {
        assertEquals("perfect",
                TitleGuard.canonicalTitle("Perfect (Acoustic)"));
        assertEquals("perfect",
                TitleGuard.canonicalTitle("Perfect - Slowed + Reverb"));
        assertEquals("perfect",
                TitleGuard.canonicalTitle("Perfect [Official Video]"));
        assertEquals("i thought i saw your face today",
                TitleGuard.canonicalTitle("I Thought I Saw Your Face Today (Live)"));
        assertEquals("i thought i saw your face today",
                TitleGuard.canonicalTitle("I Thought I Saw Your Face Today - Sped Up"));
        assertTrue("different songs stay distinct",
                !TitleGuard.canonicalTitle("Photograph").equals(TitleGuard.canonicalTitle("Shape of You")));
    }

    @Test
    public void decluster_searchWall_noBackToBackSameTitle() {
        List<Track> wall = searchWall();
        // Tapped the 3rd result, like a user tapping a search row.
        List<Track> out = TitleGuard.decluster(wall, 2);
        assertEquals("tapped track stays first", "v2", out.get(0).id);
        assertEquals("no tracks dropped", wall.size(), out.size());
        assertEquals("no adjacent same-title pairs when variety exists",
                0, TitleGuard.countAdjacentSameTitle(out));
    }

    @Test
    public void decluster_allSameTitle_keepsOrder() {
        List<Track> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) all.add(track("s" + i, "Same Song", "Artist " + i));
        List<Track> out = TitleGuard.decluster(all, 0);
        assertEquals(5, out.size());
        assertEquals("s0", out.get(0).id);
    }

    @Test
    public void smartShuffle_searchWall_noBackToBackSameTitle() {
        for (int run = 0; run < 30; run++) {
            List<Track> pool = searchWall();
            Track seed = track("seed", "I Thought I Saw Your Face Today", "Aurora Skies");
            List<Track> q = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null, null, 0.18,
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptySet(), null);
            assertEquals("no adjacent same-title pairs in curated queue, run " + run,
                    0, TitleGuard.countAdjacentSameTitle(q));
        }
    }
}

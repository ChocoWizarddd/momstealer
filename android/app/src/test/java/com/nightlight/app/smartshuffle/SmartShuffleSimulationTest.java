package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import java.util.*;

public class SmartShuffleSimulationTest {
    private static Track track(String id, String name, String artist, String album) {
        return new Track(id, name, artist, album, "", null, 200_000L, "2024");
    }
    private static List<Track> popTracks(int n) {
        List<Track> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(track("pop"+i, "Pop Song "+i, "Pop Artist "+(i%5), "Pop Album "+(i%3)));
        return out;
    }
    private static List<Track> rockTracks(int n) {
        List<Track> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(track("rock"+i, "Rock Song "+i, "Rock Band "+(i%5), "Rock Album "+(i%3)));
        return out;
    }
    private static List<Track> rnbTracks(int n) {
        List<Track> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(track("rnb"+i, "RNB Song "+i, "RNB Singer "+(i%5), "R&B Album "+(i%3)));
        return out;
    }
    private static List<Track> chillTracks(int n) {
        List<Track> out = new ArrayList<>();
        for (int i=0;i<n;i++) out.add(track("chill"+i, "Chill Song "+i, "Chill Artist "+(i%5), "Chill Album "+(i%3)));
        return out;
    }

    @Test
    public void hundredSeededSimulations_report() {
        int runs = 100;
        List<Double> sameList = new ArrayList<>();
        List<Double> relatedList = new ArrayList<>();
        List<Double> discoveryList = new ArrayList<>();
        List<Double> uniqueTracksList = new ArrayList<>();
        List<Integer> maxArtistRun = new ArrayList<>();
        int worstSame = 100, bestSame = 0;
        String[] seeds = {"Pop", "Rock", "Chill"};
        for (int run=0; run<runs; run++) {
            for (String seedGenre : seeds) {
                List<Track> pool = new ArrayList<>();
                pool.addAll(popTracks(10)); pool.addAll(rockTracks(10)); pool.addAll(rnbTracks(5));
                Track seed = track("seed"+run, "Seed Song", seedGenre.equals("Pop")?"Pop Artist 0": seedGenre.equals("Rock")?"Rock Band 0":"Chill Artist 0", seedGenre+" Album 0");
                SmartShuffleEngine engine = new SmartShuffleEngine(new Random(run));
                List<Track> q = engine.generateQueue(seed, pool, null, null, 0.18, Collections.emptySet(), Collections.emptySet(), null);
                String sg = GenreHelper.genreOf(seed);
                int same=0, related=0, disc=0;
                for (Track t: q) {
                    GenreHelper.Tier tier = GenreHelper.tier(GenreHelper.genreOf(t), sg);
                    if (tier==GenreHelper.Tier.SAME) same++; else if (tier==GenreHelper.Tier.RELATED) related++; else disc++;
                }
                double samePct = q.isEmpty()?0: same*100.0/q.size();
                double relatedPct = q.isEmpty()?0: related*100.0/q.size();
                double discPct = q.isEmpty()?0: disc*100.0/q.size();
                sameList.add(samePct); relatedList.add(relatedPct); discoveryList.add(discPct);
                if (samePct < worstSame) worstSame = (int)samePct;
                if (samePct > bestSame) bestSame = (int)samePct;
                // unique tracks
                Set<String> ids = new HashSet<>(); for (Track t: q) ids.add(t.id);
                uniqueTracksList.add(ids.size()*100.0/q.size());
                // max consecutive same artist
                int maxRun=1, cur=1;
                for (int i=1;i<q.size();i++) if (q.get(i).artists.equals(q.get(i-1).artists)) cur++; else { maxRun=Math.max(maxRun,cur); cur=1; }
                maxArtistRun.add(Math.max(maxRun,cur));
            }
        }
        double meanSame = sameList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanRelated = relatedList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanDisc = discoveryList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanUnique = uniqueTracksList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int maxRunWorst = maxArtistRun.stream().mapToInt(Integer::intValue).max().orElse(0);
        System.out.println("100 simulations mean same "+String.format("%.1f",meanSame)+"% related "+String.format("%.1f",meanRelated)+"% disc "+String.format("%.1f",meanDisc)+"% unique "+String.format("%.1f",meanUnique)+"% maxRun "+maxRunWorst+" worstSame "+worstSame+"% bestSame "+bestSame+"%");
        assertTrue("mean same should be >=30", meanSame >= 30);
        assertTrue("mean disc should be <=35", meanDisc <= 35);
        assertTrue("unique tracks should be 100%", meanUnique == 100.0);
        assertTrue("max consecutive same artist should be <=3", maxRunWorst <= 3);
    }

    @Test
    public void versionFamily_onlyOnePerCycle() {
        List<Track> pool = new ArrayList<>();
        // 4 tracks from the same "Perfect" version family
        String[] titles = {"Perfect","Perfect Acoustic","Perfect Live","Perfect Remix","Photograph","Shape of You","Thinking Out Loud","Castle on the Hill"};
        for (int i=0;i<titles.length;i++) pool.add(track("v"+i, titles[i], "Ed Sheeran", "Pop Album "+i));
        // 30 other tracks (well above MAX_QUEUE=20) so the engine can fill
        // the queue while skipping duplicate version families.
        for (int i=0;i<30;i++) pool.add(track("other"+i, "Other Song "+i, "Other Artist "+i, "Pop Album "+i));
        Track seed = track("seed", "Perfect", "Ed Sheeran", "Pop Album 0");
        int collisions=0;
        for (int run=0; run<30; run++) {
            List<Track> q = new SmartShuffleEngine(new Random(run)).generateQueue(seed, pool, null);
            // Use the same family computation as SmartShuffleEngine
            Set<String> families = new HashSet<>();
            boolean hasDuplicate = false;
            for (Track t: q) {
                String name = t.name == null ? "" : t.name.toLowerCase()
                        .replaceAll("\\b(acoustic|live|remix|reimagined|version|cover|instrumental|edit)\\b", "")
                        .trim().replaceAll("\\s+", " ");
                String artist = t.artists == null ? "" : t.artists.toLowerCase().trim();
                String fam = name + "::" + artist;
                if (!families.add(fam)) {
                    hasDuplicate = true;
                    break;
                }
            }
            if (hasDuplicate) collisions++;
        }
        assertTrue("version-family collisions should be zero when alternatives exist, got " + collisions, collisions == 0);
    }
}

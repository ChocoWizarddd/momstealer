package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SpotifyShuffler {

    public static List<Track> shuffle(List<Track> songs, String firstId) {
        return shuffle(songs, firstId, new Random());
    }

    public static List<Track> shuffle(List<Track> songs, String firstId, Random rng) {
        if (songs == null) return new ArrayList<>();
        if (songs.size() <= 1) return new ArrayList<>(songs);
        Random random = rng != null ? rng : new Random();

        Map<String, List<Track>> artistBuckets = new HashMap<>();
        for (Track s : songs) {
            String key = s.artists != null ? s.artists.toLowerCase() : "";
            artistBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        for (List<Track> bucket : artistBuckets.values()) {
            ShuffleUtils.fisherYatesShuffle(bucket, random);
        }

        for (List<Track> bucket : artistBuckets.values()) {
            spread(bucket, random);
        }

        int k = songs.size();
        double spacing = 1.0 / k;
        Map<String, Double> artistOffset = new HashMap<>();
        for (String artist : artistBuckets.keySet()) {
            artistOffset.put(artist, random.nextDouble() * spacing);
        }

        Map<String, Integer> artistIndex = new HashMap<>();
        List<PositionedSong> positioned = new ArrayList<>();

        for (Map.Entry<String, List<Track>> entry : artistBuckets.entrySet()) {
            String artist = entry.getKey();
            List<Track> bucket = entry.getValue();
            double offset = artistOffset.get(artist);
            int ki = bucket.size();
            double artistSpacing = 1.0 / ki;
            for (int i = 0; i < ki; i++) {
                double jitter = (random.nextDouble() * 2 - 1) * artistSpacing * 0.10;
                double pos = i * artistSpacing + offset + jitter;
                positioned.add(new PositionedSong(bucket.get(i), pos));
            }
        }

        Collections.sort(positioned, (a, b) -> Double.compare(a.position, b.position));

        List<Track> sorted = new ArrayList<>();
        for (PositionedSong ps : positioned) sorted.add(ps.song);

        sorted = breakUpAdjacentArtists(sorted, random);

        if (firstId != null) {
            int idx = -1;
            for (int i = 0; i < sorted.size(); i++) {
                if (firstId.equals(sorted.get(i).id)) {
                    idx = i;
                    break;
                }
            }
            if (idx > 0) {
                Track first = sorted.remove(idx);
                sorted.add(0, first);
            }
        }

        return sorted;
    }

    public static List<Track> breakUpAdjacentArtists(List<Track> songs, Random rng) {
        if (songs.size() <= 2) return songs;

        List<Track> result = new ArrayList<>(songs);

        for (int attempts = 0; attempts < 30; attempts++) {
            boolean found = false;
            for (int i = 0; i < result.size() - 1; i++) {
                String artist1 = result.get(i).artists != null ? result.get(i).artists.toLowerCase() : "";
                String artist2 = result.get(i + 1).artists != null ? result.get(i + 1).artists.toLowerCase() : "";
                if (artist1.equals(artist2)) {
                    int swapIdx = -1;
                    for (int j = i + 2; j < result.size(); j++) {
                        String artistJ = result.get(j).artists != null ? result.get(j).artists.toLowerCase() : "";
                        if (!artistJ.equals(artist1)) {
                            swapIdx = j;
                            break;
                        }
                    }
                    if (swapIdx == -1) {
                        for (int j = i - 1; j >= 0; j--) {
                            String artistJ = result.get(j).artists != null ? result.get(j).artists.toLowerCase() : "";
                            if (!artistJ.equals(artist1)) {
                                swapIdx = j;
                                break;
                            }
                        }
                    }
                    if (swapIdx >= 0) {
                        Track temp = result.get(i + 1);
                        result.set(i + 1, result.get(swapIdx));
                        result.set(swapIdx, temp);
                        found = true;
                    }
                }
            }
            if (!found) break;
        }
        return result;
    }

    private static void spread(List<Track> bucket, Random rng) {
        if (bucket.size() <= 2) return;
        int n = bucket.size();
        int half = n / 2;
        List<Track> interleaved = new ArrayList<>(n);
        for (int i = 0; i < half; i++) {
            interleaved.add(bucket.get(i));
            if (i + half < n) {
                interleaved.add(bucket.get(i + half));
            }
        }
        // Odd n: the loop above only emits half*2 entries (indices 0..2*half-1),
        // so the final track was silently dropped from the queue. Emit the tail.
        for (int i = half * 2; i < n; i++) {
            interleaved.add(bucket.get(i));
        }
        bucket.clear();
        bucket.addAll(interleaved);
    }

    private static class PositionedSong {
        Track song;
        double position;

        PositionedSong(Track song, double position) {
            this.song = song;
            this.position = position;
        }
    }
}
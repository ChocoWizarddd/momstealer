package com.nightlight.app.smartshuffle;

import java.util.List;
import java.util.Random;

/**
 * Shared unbiased shuffling utility.
 *
 * <p>Classic Fisher-Yates (Durstenfeld) shuffle: every permutation of the
 * input is equally likely. Replaces ad-hoc {@code Collections.shuffle} calls
 * so all shuffle paths (Normal / Smart candidates / Spotify buckets) share
 * one audited implementation with an injectable {@link Random} for tests.
 */
public final class ShuffleUtils {

    private ShuffleUtils() {
    }

    /**
     * Shuffles {@code list} in place using Fisher-Yates.
     *
     * @param list list to shuffle (no-op when null or size &lt; 2)
     * @param rng  source of randomness (a new {@link Random} is used when null)
     */
    public static <T> void fisherYatesShuffle(List<T> list, Random rng) {
        if (list == null || list.size() < 2) {
            return;
        }
        Random random = rng != null ? rng : new Random();
        // Walk from the end: swap element i with a uniformly random
        // element in [0, i]. Each of the n! permutations is equally likely.
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            if (j != i) {
                T tmp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, tmp);
            }
        }
    }

    /**
     * Shuffles {@code list} in place using a fresh {@link Random}.
     * Prefer the {@link #fisherYatesShuffle(List, Random)} overload in
     * production paths so tests can inject a seeded RNG.
     */
    public static <T> void fisherYatesShuffle(List<T> list) {
        fisherYatesShuffle(list, new Random());
    }
}

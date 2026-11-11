package com.nightlight.app.playback;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.shuffle.ShuffleOrderFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One playback context plus the fixed order it is being played through, and the
 * cursor into that order.
 *
 * <p>Order is decided once, when the session is created:
 *
 * <ul>
 *   <li><b>Shuffle off</b> — the context's listed order starting at the track
 *       the user chose. Playback continues forward through the context and does
 *       <i>not</i> wrap back to the tracks above the selection.</li>
 *   <li><b>Shuffle on</b> — one complete permutation of the whole context,
 *       then <b>rotated</b> so the chosen track is first. Rotating rather than
 *       regenerating is what lets every track still play exactly once per cycle
 *       while starting on the user's pick.</li>
 * </ul>
 *
 * <p>After that the order is frozen. NEXT moves the cursor, refill exposes more
 * of the same order, and nothing here calls random() again.
 */
public final class PlaybackSession {

    private final PlaybackContext context;
    private final List<String> order;
    private final boolean shuffle;
    private final Set<String> playedIds = new LinkedHashSet<>();
    private int cursor;

    private PlaybackSession(PlaybackContext context, List<String> order, boolean shuffle) {
        this.context = context;
        this.order = Collections.unmodifiableList(order);
        this.shuffle = shuffle;
    }

    public static PlaybackSession sequential(PlaybackContext context) {
        List<String> ids = new ArrayList<>();
        for (Track t : context.tracksAfterAnchor()) ids.add(t.id);
        return new PlaybackSession(context, ids, false);
    }

    public static PlaybackSession shuffled(PlaybackContext context, long seed) {
        List<String> full = ShuffleOrderFactory.plainOrder(context.tracks(), seed);
        return new PlaybackSession(context, rotateTo(full, context.anchorTrackId()), true);
    }

    public static PlaybackSession smart(PlaybackContext context, long seed, List<String> recentlyPlayedIds) {
        List<String> full = ShuffleOrderFactory.smartOrder(context.tracks(), seed, recentlyPlayedIds);
        return new PlaybackSession(context, rotateTo(full, context.anchorTrackId()), true);
    }

    /** Builds whichever session the effective shuffle setting calls for. */
    public static PlaybackSession create(PlaybackContext context, boolean smartShuffle,
                                         long seed, List<String> recentlyPlayedIds) {
        return smartShuffle ? smart(context, seed, recentlyPlayedIds) : shuffled(context, seed);
    }

    /**
     * Rotates a fixed order so {@code anchorId} comes first, preserving the
     * relative order of everything else. Falls back to the untouched order when
     * the anchor is absent.
     */
    static List<String> rotateTo(List<String> order, String anchorId) {
        if (anchorId == null || anchorId.isEmpty() || order == null || order.isEmpty()) {
            return order == null ? new ArrayList<String>() : new ArrayList<>(order);
        }
        int at = order.indexOf(anchorId);
        if (at <= 0) return new ArrayList<>(order);
        List<String> out = new ArrayList<>(order.size());
        out.addAll(order.subList(at, order.size()));
        out.addAll(order.subList(0, at));
        return out;
    }

    public PlaybackContext context() {
        return context;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public List<String> order() {
        return order;
    }

    public int cursor() {
        return cursor;
    }

    public int size() {
        return order.size();
    }

    public boolean isEmpty() {
        return order.isEmpty();
    }

    public boolean isExhausted() {
        return cursor >= order.size();
    }

    public Set<String> playedIds() {
        return Collections.unmodifiableSet(playedIds);
    }

    public String currentId() {
        return cursor > 0 && cursor <= order.size() ? order.get(cursor - 1) : null;
    }

    public String peekNextId() {
        return cursor < order.size() ? order.get(cursor) : null;
    }

    public String peekPreviousId() {
        return cursor > 0 ? order.get(cursor - 1) : null;
    }

    public String advance() {
        if (isExhausted()) return null;
        String id = order.get(cursor);
        cursor++;
        playedIds.add(id);
        return id;
    }

    public String rewind() {
        if (cursor <= 0) return null;
        cursor--;
        String id = order.get(cursor);
        playedIds.remove(id);
        return id;
    }

    /** Next {@code size} unplayed ids of this same order. */
    public List<String> upcoming(int size) {
        if (isExhausted() || size <= 0) return Collections.emptyList();
        int end = Math.min(order.size(), cursor + size);
        return new ArrayList<>(order.subList(cursor, end));
    }

    /** Lazily built id -> position map so track-start sync stays O(1). */
    private java.util.Map<String, Integer> indexById;

    private int indexOf(String id) {
        if (id == null) return -1;
        if (indexById == null) {
            indexById = new java.util.HashMap<>(order.size() * 2);
            for (int i = 0; i < order.size(); i++) indexById.putIfAbsent(order.get(i), i);
        }
        Integer idx = indexById.get(id);
        return idx != null ? idx : -1;
    }

    /**
     * Moves the cursor so {@code id} becomes the current track.
     *
     * <p>Used when the player starts a track by a route other than the session
     * (a queue-sheet jump, a Previous tap, an auto-advance). Tracks skipped
     * forward are marked played; tracks stepped back over are un-marked so the
     * order stays consistent in both directions. The ORDER ITSELF NEVER CHANGES.
     *
     * @return true when the id was found in this session's order
     */
    public boolean syncCursorTo(String id) {
        int target = indexOf(id);
        if (target < 0) return false;
        int wanted = target + 1;
        if (wanted > cursor) {
            for (int i = cursor; i < wanted; i++) playedIds.add(order.get(i));
        } else if (wanted < cursor) {
            for (int i = wanted; i < cursor; i++) playedIds.remove(order.get(i));
        }
        cursor = wanted;
        return true;
    }

    /** The unplayed id {@code offset} steps ahead of the cursor, or null. */
    public String peekIdAt(int offset) {
        int index = cursor + offset;
        if (offset < 0 || index < 0 || index >= order.size()) return null;
        return order.get(index);
    }

    /** How many tracks of this order have not played yet. */
    public int remainingCount() {
        return Math.max(0, order.size() - cursor);
    }

    public List<String> remainingIds() {
        return remaining();
    }

    public List<String> remaining() {
        if (isExhausted()) return Collections.emptyList();
        return new ArrayList<>(order.subList(cursor, order.size()));
    }

    public Track trackById(String id) {
        return context.trackById(id);
    }

    public static long randomSeed() {
        return new Random().nextLong();
    }
}
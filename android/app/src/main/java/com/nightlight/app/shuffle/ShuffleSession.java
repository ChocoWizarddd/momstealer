package com.nightlight.app.shuffle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical shuffle session: ONE complete randomized order plus a cursor.
 *
 * <p>The order is produced once, when the session starts, and is immutable for
 * the lifetime of the cycle. Playback only ever moves the cursor. Nothing in
 * this class generates, reshuffles, truncates or re-orders {@link #order} in
 * response to queue length, refills, re-renders, resume or NEXT taps.
 *
 * <p>A cycle ends when the cursor reaches the end of the order. Only then does
 * a caller start a new session (new seed, therefore a genuinely new order).
 */
public final class ShuffleSession {

    public String sessionId = "";
    public long seed;
    public String sourcePlaylistId = "";
    public boolean smart;
    public int cycle;
    public long createdAt;

    private List<String> order = Collections.emptyList();
    private final Set<String> playedIds = new LinkedHashSet<>();
    private int cursor;

    public ShuffleSession() {
    }

    public ShuffleSession(String sessionId, long seed, String sourcePlaylistId, boolean smart,
                          int cycle, long createdAt, List<String> order) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.seed = seed;
        this.sourcePlaylistId = sourcePlaylistId != null ? sourcePlaylistId : "";
        this.smart = smart;
        this.cycle = cycle;
        this.createdAt = createdAt;
        setOrder(order);
    }

    public void setOrder(List<String> ids) {
        List<String> copy = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty() && !copy.contains(id)) copy.add(id);
            }
        }
        this.order = Collections.unmodifiableList(copy);
        this.playedIds.clear();
        this.cursor = 0;
    }

    /**
     * Restores order, played set and cursor verbatim. Unlike {@link #setOrder}
     * followed by {@link #seekTo}, this does not re-derive the played set from
     * the cursor, so a session that was rewound before being persisted comes
     * back with exactly the tracks it had played.
     */
    public void restore(List<String> ids, Collection<String> played, int index) {
        setOrder(ids);
        playedIds.clear();
        if (played != null) {
            for (String id : played) {
                if (id != null && !id.isEmpty() && order.contains(id)) playedIds.add(id);
            }
        }
        cursor = Math.max(0, Math.min(index, order.size()));
    }

    public List<String> order() {
        return order;
    }

    public Set<String> playedIds() {
        return Collections.unmodifiableSet(playedIds);
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

    public boolean isComplete() {
        return cursor >= order.size();
    }

    public boolean hasNext() {
        return cursor < order.size();
    }

    public boolean hasPrevious() {
        return cursor > 0;
    }

    public String peekNextId() {
        return hasNext() ? order.get(cursor) : null;
    }

    public String peekPreviousId() {
        return hasPrevious() ? order.get(cursor - 1) : null;
    }

    public String currentId() {
        return cursor > 0 && cursor <= order.size() ? order.get(cursor - 1) : null;
    }

    /** Moves the cursor forward and returns the id now current, or null at end. */
    public String advance() {
        if (!hasNext()) return null;
        String id = order.get(cursor);
        cursor++;
        playedIds.add(id);
        return id;
    }

    /**
     * Moves the cursor backward. The id being left is un-played again so the
     * current cycle still contains it exactly once.
     */
    public String rewind() {
        if (!hasPrevious()) return null;
        cursor--;
        String id = order.get(cursor);
        playedIds.remove(id);
        return id;
    }

    /** Jump to an absolute cursor position, keeping played state consistent. */
    public void seekTo(int index) {
        int clamped = Math.max(0, Math.min(index, order.size()));
        if (clamped < cursor) {
            for (int i = clamped; i < cursor; i++) playedIds.remove(order.get(i));
        } else {
            for (int i = cursor; i < clamped; i++) playedIds.add(order.get(i));
        }
        cursor = clamped;
    }

    public void markPlayed(String id) {
        if (id != null && !id.isEmpty()) playedIds.add(id);
    }

    public boolean hasPlayed(String id) {
        return id != null && playedIds.contains(id);
    }

    /** Remaining unplayed ids in session order. Never reorders them. */
    public List<String> remaining() {
        if (!hasNext()) return Collections.emptyList();
        return new ArrayList<>(order.subList(cursor, order.size()));
    }

    public List<String> remaining(int limit) {
        if (!hasNext() || limit <= 0) return Collections.emptyList();
        int end = Math.min(order.size(), cursor + limit);
        return new ArrayList<>(order.subList(cursor, end));
    }

    public boolean matchesSource(String sourceId) {
        String a = sourcePlaylistId != null ? sourcePlaylistId : "";
        String b = sourceId != null ? sourceId : "";
        return a.equals(b);
    }

    /** True when this session can be resumed as-is for the given source. */
    public boolean isResumable(String sourceId, boolean smartMode) {
        return smartMode == smart && matchesSource(sourceId) && !isEmpty();
    }

    @Override
    public String toString() {
        return "ShuffleSession{" + sessionId + " src=" + sourcePlaylistId + " smart=" + smart
                + " cycle=" + cycle + " cursor=" + cursor + "/" + order.size() + "}";
    }
}
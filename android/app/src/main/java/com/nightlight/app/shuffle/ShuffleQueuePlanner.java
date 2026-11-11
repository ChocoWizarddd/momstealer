package com.nightlight.app.shuffle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The queue is a WINDOW into the active session order — never a second order.
 *
 * <p>{@link #refill} is the only sanctioned way to grow the queue while a
 * shuffle session is active, and it is idempotent: it appends exactly the
 * session-order ids that are neither already queued nor already played, in
 * session order. Calling it repeatedly with an unchanged queue returns nothing,
 * and calling it after tracks have been consumed tops the queue back up from
 * the SAME order rather than generating a new one.
 */
public final class ShuffleQueuePlanner {

    public static final int DEFAULT_WINDOW = 10;

    private ShuffleQueuePlanner() {
    }

    /** Next {@code size} unplayed ids from the session order, in order. */
    public static List<String> window(ShuffleSession session, int size) {
        if (session == null || session.isEmpty()) return Collections.emptyList();
        return session.remaining(size);
    }

    /**
     * Ids to append so the queue holds the next {@code windowSize} upcoming
     * session tracks.
     *
     * @param session       active session; its order is read-only here
     * @param queuedIds     ids currently in the player queue (any order)
     * @param playedIds     ids already consumed this cycle
     * @param windowSize    desired number of UPCOMING tracks to keep queued
     */
    public static List<String> refill(ShuffleSession session, Collection<String> queuedIds,
                                      Collection<String> playedIds, int windowSize) {
        if (session == null || session.isEmpty() || windowSize <= 0) return Collections.emptyList();

        Set<String> queued = queuedIds != null ? new HashSet<>(queuedIds) : new HashSet<>();
        Set<String> played = new HashSet<>();
        if (playedIds != null) played.addAll(playedIds);
        played.addAll(session.playedIds());

        int upcoming = 0;
        for (String id : queued) {
            if (!played.contains(id)) upcoming++;
        }

        int need = windowSize - upcoming;
        if (need <= 0) return Collections.emptyList();

        List<String> out = new ArrayList<>(need);
        for (String id : session.remaining()) {
            if (need <= 0) break;
            if (played.contains(id)) continue;
            if (queued.contains(id)) continue;
            out.add(id);
            queued.add(id);
            need--;
        }
        return out;
    }

    /**
     * Play-next (manual) items are spliced in AFTER the current track and
     * BEFORE the next context track, without entering the session order.
     */
    public static List<String> mergeManualNext(Collection<String> manualNext, String nextContextId) {
        List<String> out = new ArrayList<>();
        if (manualNext != null) {
            for (String id : manualNext) {
                if (id != null && !id.isEmpty()) out.add(id);
            }
        }
        if (nextContextId != null && !nextContextId.isEmpty()) out.add(nextContextId);
        return out;
    }

    /** Distinct, order-preserving de-duplication helper for queue snapshots. */
    public static List<String> distinct(Collection<String> ids) {
        if (ids == null) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isEmpty()) out.add(id);
        }
        return new ArrayList<>(out);
    }
}
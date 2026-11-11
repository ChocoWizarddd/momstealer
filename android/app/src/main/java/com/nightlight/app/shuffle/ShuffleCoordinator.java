package com.nightlight.app.shuffle;

import com.nightlight.app.domain.model.Track;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The single owner of the active shuffle session.
 *
 * <p>This is the only place allowed to CREATE a shuffle order, and it creates
 * one in exactly four situations:
 *
 * <ol>
 *   <li>the user turns Shuffle ON,</li>
 *   <li>the user explicitly reshuffles,</li>
 *   <li>the current cycle is exhausted and another cycle is required,</li>
 *   <li>the playback context is intentionally replaced by a new source.</li>
 * </ol>
 *
 * <p>Everything else — NEXT, PREVIOUS, queue refill, a React/ViewModel
 * re-render, app resume, a playback callback, a low queue — calls
 * {@link #peekNextId()}, {@link #advance()}, {@link #rewind()} or
 * {@link #refillPlan}. None of them regenerate.
 */
public final class ShuffleCoordinator {

    private final ShuffleSessionStore store;
    private final ManualQueue manualQueue = new ManualQueue();
    private ShuffleSession session;

    public ShuffleCoordinator() {
        this(null);
    }

    public ShuffleCoordinator(ShuffleSessionStore store) {
        this.store = store;
        if (store != null) {
            this.session = store.load();
            manualQueue.restore(store.loadManual());
        }
    }

    public synchronized ShuffleSession session() {
        return session;
    }

    public synchronized ManualQueue manualQueue() {
        return manualQueue;
    }

    public synchronized boolean isActive() {
        return session != null && !session.isEmpty();
    }

    public synchronized int cycle() {
        return session != null ? session.cycle : 0;
    }

    /**
     * Returns the active session for this source, creating one only when there
     * is none, the source changed, the mode changed, or the order is unusable.
     * An in-progress session for the same source is resumed untouched.
     */
    public synchronized ShuffleSession ensureSession(List<Track> pool, String sourceId,
                                                    boolean smartMode, List<String> recentlyPlayedIds) {
        if (session != null && session.isResumable(sourceId, smartMode)) return session;
        return createSession(pool, sourceId, smartMode, recentlyPlayedIds, nextCycle());
    }

    /** Explicit user action: Shuffle ON or Reshuffle. Always a brand new order. */
    public synchronized ShuffleSession startNewSession(List<Track> pool, String sourceId,
                                                      boolean smartMode, List<String> recentlyPlayedIds) {
        return createSession(pool, sourceId, smartMode, recentlyPlayedIds, nextCycle());
    }

    /**
     * Called only when the current cycle is fully consumed and playback needs
     * another one. The new cycle gets a fresh seed, so it is a genuinely new
     * order rather than a replay of the previous one.
     */
    public synchronized ShuffleSession startNextCycle(List<Track> pool, String sourceId,
                                                     boolean smartMode, List<String> recentlyPlayedIds) {
        if (session == null || session.isEmpty()) {
            return createSession(pool, sourceId, smartMode, recentlyPlayedIds, 0);
        }
        return createSession(pool, sourceId, smartMode, recentlyPlayedIds, session.cycle + 1);
    }

    /** Context intentionally replaced (different playlist/source). */
    public synchronized void invalidate() {
        session = null;
        if (store != null) store.clear();
    }

    public synchronized String peekNextId() {
        return session != null ? session.peekNextId() : null;
    }

    public synchronized String peekPreviousId() {
        return session != null ? session.peekPreviousId() : null;
    }

    /** Cursor move only. Never calls random(). */
    public synchronized String advance() {
        return session != null ? session.advance() : null;
    }

    /** Cursor move only. Never calls random(). */
    public synchronized String rewind() {
        return session != null ? session.rewind() : null;
    }

    public synchronized void seekTo(int index) {
        if (session != null) session.seekTo(index);
        persist();
    }

    public synchronized boolean isCycleComplete() {
        return session != null && session.isComplete();
    }

    /** Ids to append so the queue holds the next {@code windowSize} session tracks. */
    public synchronized List<String> refillPlan(Collection<String> queuedIds, int windowSize) {
        if (session == null) return java.util.Collections.emptyList();
        return ShuffleQueuePlanner.refill(session, queuedIds, session.playedIds(), windowSize);
    }

    public synchronized void persist() {
        if (store == null || session == null) return;
        store.save(session);
        store.saveManual(manualQueue.snapshot());
    }

    private int nextCycle() {
        return session != null ? session.cycle + 1 : 0;
    }

    private ShuffleSession createSession(List<Track> pool, String sourceId, boolean smartMode,
                                         List<String> recentlyPlayedIds, int cycle) {
        long seed = new Random().nextLong();
        List<String> order = smartMode
                ? ShuffleOrderFactory.smartOrder(pool, seed, recentlyPlayedIds)
                : ShuffleOrderFactory.plainOrder(pool, seed);
        session = new ShuffleSession("shuffle-" + UUID.randomUUID(), seed,
                sourceId != null ? sourceId : "", smartMode, cycle, System.currentTimeMillis(), order);
        persist();
        return session;
    }
}
package com.nightlight.app.shuffle;

import com.nightlight.app.domain.model.Track;
import com.nightlight.app.playback.PlaybackContext;
import com.nightlight.app.playback.PlaybackSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns queue COMPOSITION — which tracks the player should be holding right now.
 *
 * <p>This class deliberately owns no ordering logic. The order is created once by
 * {@link ShuffleOrderFactory} and held by {@link PlaybackSession}; the
 * {@link QueueManager} only decides how much of that order to expose at a time
 * and where the manual "Play Next" items interleave. It can therefore never
 * reshuffle, and a test can assert that directly.
 *
 * <p>Composition order is:
 * <pre>
 *   [currently playing] [manual Play Next items] [upcoming context tracks]
 * </pre>
 * Manual items sit directly after the current track — that is what "Play Next"
 * means — while context tracks keep following the session order behind them. A
 * manual item never enters the session order, so the context resumes exactly
 * where it left off once the manual item finishes.
 *
 * <p>Refill is idempotent and single-flight. Rapid QUEUE_LOW / STATE_ENDED
 * bursts are collapsed into one logical refill: {@link #refill} returns the
 * index of the first appended track so the caller plays the new material rather
 * than restarting the window, and a refill already in progress makes any
 * re-entrant call a no-op.
 *
 * <p>All methods run on the caller's thread and hold no locks of their own;
 * {@link PlaybackManager} already confines playback to the main thread.
 */
public final class QueueManager {

    public static final int DEFAULT_WINDOW = 10;

    /** Outcome of one refill attempt, including why it did nothing. */
    public static final class Refill {
        /** Index the first appended track lands at, or -1 when nothing was added. */
        public final int firstNewIndex;
        /** Ids actually appended, in session order. */
        public final List<String> addedIds;
        /** Monotonic id, useful for correlating log lines. */
        public final long requestId;
        /** True when a refill was already running and this call was collapsed. */
        public final boolean coalesced;

        Refill(int firstNewIndex, List<String> addedIds, long requestId, boolean coalesced) {
            this.firstNewIndex = firstNewIndex;
            this.addedIds = Collections.unmodifiableList(addedIds);
            this.requestId = requestId;
            this.coalesced = coalesced;
        }

        static final Refill NONE = new Refill(-1, Collections.<String>emptyList(), 0L, false);

        public boolean addedAnything() {
            return !addedIds.isEmpty();
        }

        public int firstNewIndex() {
            return firstNewIndex;
        }

        public List<String> addedIds() {
            return addedIds;
        }

        public long requestId() {
            return requestId;
        }

        /** True when this call was folded into a refill already in progress. */
        public boolean coalesced() {
            return coalesced;
        }

        @Override
        public String toString() {
            return "Refill{req=" + requestId + " added=" + addedIds.size()
                    + " at=" + firstNewIndex + (coalesced ? " coalesced" : "") + "}";
        }
    }

    private PlaybackSession session;
    private final ManualQueue manualQueue = new ManualQueue();

    /**
     * The actual Track behind each manual id. Manual items very often come from
     * outside the active context, so the session cannot resolve them on its own.
     */
    private final java.util.Map<String, Track> manualTracks = new java.util.LinkedHashMap<>();

    private final int windowSize;

    private long refillRequestCounter;
    private long generation;
    private boolean refillInFlight;

    public QueueManager() {
        this(DEFAULT_WINDOW);
    }

    public QueueManager(int windowSize) {
        this.windowSize = Math.max(1, windowSize);
    }

    // ------------------------------------------------------------- session

    /**
     * Installs the one session whose order this manager will serve. Any previous
     * session is discarded and the generation counter advances so in-flight
     * refills against the old order can be recognised as stale.
     */
    public void beginSession(PlaybackSession session) {
        this.session = session;
        this.generation++;
        this.manualQueue.clear();
        this.manualTracks.clear();
        this.refillInFlight = false;
    }

    public PlaybackSession session() {
        return session;
    }

    public PlaybackContext context() {
        return session != null ? session.context() : null;
    }

    public ManualQueue manualQueue() {
        return manualQueue;
    }

    public long generation() {
        return generation;
    }

    public int windowSize() {
        return windowSize;
    }

    /** Drops the session; the queue is empty until the next playback start. */
    public void clear() {
        session = null;
        generation++;
        manualQueue.clear();
        manualTracks.clear();
        refillInFlight = false;
    }

    // ---------------------------------------------------------- composition

    /**
     * Consumes the next track from the session order and returns the window that
     * should now be playing: the track just consumed, any manual items queued
     * against it, then the upcoming context tracks.
     *
     * <p>This is the ONLY place the cursor moves forward on a fresh start.
     */
    public List<Track> startWindow() {
        if (session == null) return Collections.emptyList();
        if (!session.isExhausted()) session.advance();
        return composeWindow();
    }

    /**
     * Rebuilds the window for an ALREADY RUNNING session without consuming a
     * track.
     *
     * <p>Backgrounding and resuming, rebuilding the player queue after a media
     * session reconnect, and toggling UI state all land here. Advancing here
     * would silently skip a track on every resume, so composition is the whole
     * job.
     */
    public List<Track> resumeWindow() {
        return composeWindow();
    }

    /**
     * The current window without moving the cursor. Safe to call repeatedly —
     * composition must never have a side effect on the order.
     */
    public List<Track> composeWindow() {
        if (session == null) return Collections.emptyList();
        List<Track> out = new ArrayList<>();
        String currentId = session.currentId();
        if (currentId != null) {
            Track current = session.trackById(currentId);
            if (current != null) out.add(current);
        }
        out.addAll(resolveManual());
        out.addAll(upcoming());
        return out;
    }

    /** Upcoming context tracks, capped at the window size. */
    public List<Track> upcoming() {
        if (session == null) return Collections.emptyList();
        List<Track> out = new ArrayList<>();
        int limit = Math.min(windowSize, session.remainingCount());
        for (int i = 0; i < limit; i++) {
            String id = session.peekIdAt(i);
            Track t = id != null ? session.trackById(id) : null;
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * Manual items resolve against the session first so they keep the context's
     * metadata, then against the track captured when the user queued them. The
     * second lookup matters because "Play Next" is usually a track from OUTSIDE
     * the current context, which the session cannot resolve.
     */
    private List<Track> resolveManual() {
        List<Track> out = new ArrayList<>();
        for (String id : manualQueue.snapshot()) {
            Track t = session != null ? session.trackById(id) : null;
            if (t == null) t = manualTracks.get(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * Registers a "Play Next" item. It lands directly after the current track and
     * is deliberately NOT added to the session order.
     */
    public void playNext(Track track) {
        if (track == null || track.id == null) return;
        manualTracks.put(track.id, track);
        manualQueue.playNext(track.id);
    }

    // -------------------------------------------------------------- refill

    /**
     * Tops the queue up from the SAME session order so the player keeps
     * {@code windowSize} upcoming context tracks.
     *
     * <p>Idempotent: a queue that already holds enough upcoming tracks adds
     * nothing. Single-flight: a re-entrant call while a refill is running is
     * coalesced rather than producing a second append.
     *
     * @param upcomingCount upcoming context tracks the player already holds
     * @param playerQueueSize the player queue length BEFORE appending, so the
     *                        caller can seek to {@link Refill#firstNewIndex()}
     *                        and play the new material instead of restarting
     *                        the window
     * @return where the appended tracks begin, or -1 if nothing was added
     */
    public Refill refill(int upcomingCount) {
        return refill(upcomingCount, 0);
    }

    /**
     * Tops the queue up from the SAME session order so the player keeps
     * {@code windowSize} upcoming context tracks.
     *
     * <p>This is a PURE READ of the order. It deliberately does not move the
     * cursor: appending tracks to the player queue does not play them, so the
     * cursor must stay on the next track that will actually sound. Advancing it
     * here was a real bug — it made the appended tracks vanish from the composed
     * window and let the next refill re-queue tracks already held, duplicating
     * the queue.
     *
     * <p>Because the already-queued prefix is skipped by POSITION rather than
     * re-derived from a count of plays, a repeated QUEUE_LOW with the same
     * window reports the same empty result: refill is idempotent.
     *
     * @param upcomingCount upcoming context tracks the player already holds
     * @param playerQueueSize the player queue length BEFORE appending, so the
     *                        caller can seek to {@link Refill#firstNewIndex()}
     *                        and play the new material instead of restarting
     *                        the window
     * @return where the appended tracks begin, or -1 if nothing was added
     */
    public Refill refill(int upcomingCount, int playerQueueSize) {
        if (session == null) return Refill.NONE;
        long requestId = ++refillRequestCounter;
        int required = Math.max(0, windowSize - Math.max(0, upcomingCount));
        if (required == 0) {
            return new Refill(-1, Collections.<String>emptyList(), requestId, false);
        }
        List<String> add = new ArrayList<>(required);
        for (int i = 0; i < required; i++) {
            String id = session.peekIdAt(upcomingCount + i);
            if (id == null) break;
            add.add(id);
        }
        if (add.isEmpty()) {
            return new Refill(-1, Collections.<String>emptyList(), requestId, false);
        }
        return new Refill(Math.max(0, playerQueueSize), add, requestId, false);
    }

    /**
     * The next context track that will sound, consuming it. Playback calls this
     * when a track actually STARTS — never when tracks are merely queued.
     */
    public String advance() {
        return session != null ? session.advance() : null;
    }
}

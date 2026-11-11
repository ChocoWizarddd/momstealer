package com.nightlight.app.reco;

/**
 * Single-flight + staleness guard for Smart Shuffle recommendation work
 * (§17–19, §26 J–M). Exactly one batch-generation operation may own the
 * session at a time; every async response is validated against the
 * (sessionId, generation) pair that requested it, and anything stale is
 * discarded — never appended, never replacing newer queues.
 *
 * States: IDLE → GENERATING → READY → PLAYING ⇄ TOPPING_UP, with
 * ERROR_RECOVERABLE as a non-terminal fault sink (playback never blocks).
 * Pure Java — fully unit-testable without Android or Media3.
 */
public final class GenerationGuard {

    public enum State {
        IDLE, GENERATING, READY, PLAYING, TOPPING_UP, ERROR_RECOVERABLE
    }

    private String sessionId = "";
    private long generation = 0;
    private long activeGen = -1;
    private boolean generating = false;
    private State state = State.IDLE;
    private final java.util.List<String> transitions = new java.util.ArrayList<>();

    private void log(String event) {
        transitions.add(event);
        if (transitions.size() > 40) transitions.remove(0);
    }

    /** Starts a fresh session: new id, generation 0, single-flight released. */
    public synchronized String beginSession() {
        sessionId = java.util.UUID.randomUUID().toString();
        generation = 0;
        activeGen = -1;
        generating = false;
        state = State.IDLE;
        log("beginSession");
        return sessionId;
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isGenerating() {
        return generating;
    }

    /**
     * Acquires the single flight for a batch generation. Returns the new
     * generation id, or -1 when the session is stale or a generation is
     * already running (caller must NOT start another one — top-ups skip).
     */
    public synchronized long startGeneration(String session) {
        if (session == null || !session.equals(sessionId) || sessionId.isEmpty()) {
            return -1;
        }
        if (generating) {
            return -1;
        }
        generating = true;
        generation++;
        activeGen = generation;
        state = (state == State.PLAYING || state == State.READY || state == State.TOPPING_UP)
                ? State.TOPPING_UP : State.GENERATING;
        log("start gen=" + generation + " state=" + state);
        return generation;
    }

    /**
     * Releases the flight. Only the owning generation releases (stale
     * finishers are ignored without disturbing newer work). Returns false
     * when the response must be DISCARDED.
     */
    public synchronized boolean finishGeneration(String session, long gen, boolean hasQueue) {
        if (session == null || !session.equals(sessionId) || gen != activeGen) {
            return false;
        }
        generating = false;
        activeGen = -1;
        if (hasQueue) {
            state = State.PLAYING;
        } else {
            state = State.READY;
        }
        log("finish gen=" + gen + " hasQueue=" + hasQueue + " state=" + state);
        return true;
    }

    /** Marks a recoverable fault without blocking playback. */
    public synchronized void markError(String session, long gen) {
        if (session != null && session.equals(sessionId) && gen == activeGen) {
            generating = false;
            activeGen = -1;
            state = State.ERROR_RECOVERABLE;
            log("error gen=" + gen);
        }
    }

    /** Force-releases a stuck flight (watchdog): never wedge top-ups forever. */
    public synchronized void forceRelease(String session) {
        if (session != null && session.equals(sessionId) && generating) {
            generating = false;
            activeGen = -1;
            state = State.ERROR_RECOVERABLE;
            log("forceRelease");
        }
    }

    /** Playback entered PLAYING with a ready queue. */
    public synchronized void onPlaying(String session) {
        if (session != null && session.equals(sessionId)
                && (state == State.READY || state == State.GENERATING
                    || state == State.TOPPING_UP || state == State.ERROR_RECOVERABLE)) {
            state = State.PLAYING;
            log("playing");
        }
    }

    /** True only for the live session+generation pair. */
    public synchronized boolean isCurrent(String session, long gen) {
        return session != null && session.equals(sessionId) && gen == activeGen;
    }

    /** Invalidates everything (mode off, logout, session reset). */
    public synchronized void reset() {
        sessionId = "";
        generation = 0;
        activeGen = -1;
        generating = false;
        state = State.IDLE;
        log("reset");
    }

    synchronized java.util.List<String> transitionLog() {
        return new java.util.ArrayList<>(transitions);
    }
}

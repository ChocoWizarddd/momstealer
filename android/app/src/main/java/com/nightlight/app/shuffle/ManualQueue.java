package com.nightlight.app.shuffle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Manual "Play Next" items, kept strictly OUTSIDE the shuffle session.
 *
 * <p>Adding X here must not mutate the session order, must not consume a
 * session slot, and must not shift the cursor. When X finishes, playback
 * resumes at exactly the session position it was on before X was inserted.
 */
public final class ManualQueue {

    private final Deque<String> next = new ArrayDeque<>();

    public synchronized void playNext(String trackId) {
        if (trackId == null || trackId.isEmpty()) return;
        next.remove(trackId);
        next.addFirst(trackId);
    }

    public synchronized void playLast(String trackId) {
        if (trackId == null || trackId.isEmpty()) return;
        next.remove(trackId);
        next.addLast(trackId);
    }

    public synchronized void remove(String trackId) {
        next.remove(trackId);
    }

    public synchronized String peek() {
        return next.peekFirst();
    }

    public synchronized String poll() {
        return next.pollFirst();
    }

    public synchronized boolean isEmpty() {
        return next.isEmpty();
    }

    public synchronized int size() {
        return next.size();
    }

    public synchronized void clear() {
        next.clear();
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(next);
    }

    public synchronized void restore(List<String> ids) {
        next.clear();
        if (ids == null) return;
        for (String id : ids) {
            if (id != null && !id.isEmpty()) next.addLast(id);
        }
    }
}
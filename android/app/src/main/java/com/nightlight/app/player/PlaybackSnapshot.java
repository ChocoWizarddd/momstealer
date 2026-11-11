package com.nightlight.app.player;

import com.nightlight.app.domain.model.Track;

/** Single source of truth for what the UI shows about playback. */
public final class PlaybackSnapshot {

    public static final PlaybackSnapshot EMPTY = new PlaybackSnapshot(
            null, false, false, 0L, 0L, false, 0, false, null, 0, -1, false, null);

    public final Track current;
    public final boolean isPlaying;
    public final boolean isBuffering;
    public final long position;
    public final long duration;
    public final boolean hasQueue;
    public final int repeatMode; // 0 off, 1 all, 2 one
    public final boolean shuffle;
    public final String error;
    public final int queueSize;
    public final int currentIndex;
    public final boolean connected;
    /** Stream quality line (e.g. "44.1 KHZ • 274 KBPS • MP3"), null when unknown. */
    public final String audioLine;

    public PlaybackSnapshot(Track current, boolean isPlaying, boolean isBuffering, long position,
                            long duration, boolean hasQueue, int repeatMode, boolean shuffle,
                            String error, int queueSize, int currentIndex, boolean connected,
                            String audioLine) {
        this.current = current;
        this.isPlaying = isPlaying;
        this.isBuffering = isBuffering;
        this.position = position;
        this.duration = duration;
        this.hasQueue = hasQueue;
        this.repeatMode = repeatMode;
        this.shuffle = shuffle;
        this.error = error;
        this.queueSize = queueSize;
        this.currentIndex = currentIndex;
        this.connected = connected;
        this.audioLine = audioLine;
    }

    /** Compares the fields that matter to the UI (position excluded at tick level). */
    public boolean equalsContent(PlaybackSnapshot other) {
        if (other == null) {
            return false;
        }
        String thisId = current != null ? current.id : null;
        String otherId = other.current != null ? other.current.id : null;
        return java.util.Objects.equals(thisId, otherId)
                && isPlaying == other.isPlaying
                && isBuffering == other.isBuffering
                && hasQueue == other.hasQueue
                && repeatMode == other.repeatMode
                && shuffle == other.shuffle
                && java.util.Objects.equals(error, other.error)
                && queueSize == other.queueSize
                && currentIndex == other.currentIndex
                && java.util.Objects.equals(audioLine, other.audioLine);
    }
}
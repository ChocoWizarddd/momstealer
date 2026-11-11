package com.nightlight.app.playback;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resolved playback context: what collection playback is running inside, and
 * which track in it the user chose.
 *
 * <p>This is the answer to "what is my current context" and "why is this track
 * playing". It is immutable; the cursor lives in the session that consumes it.
 */
public final class PlaybackContext {

    public final PlaybackContextType type;
    public final String contextId;
    private final List<Track> tracks;
    private final Map<String, Track> byId = new LinkedHashMap<>();
    private final String anchorTrackId;
    private final int anchorIndex;

    private PlaybackContext(PlaybackContextType type, String contextId, List<Track> tracks,
                            String anchorTrackId, int anchorIndex) {
        this.type = type;
        this.contextId = contextId != null ? contextId : "";
        this.tracks = Collections.unmodifiableList(tracks);
        this.anchorTrackId = anchorTrackId;
        this.anchorIndex = anchorIndex;
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t != null && t.id != null && !byId.containsKey(t.id)) byId.put(t.id, t);
        }
    }

    public static PlaybackContext of(PlaybackContextType type, String contextId, List<Track> tracks,
                                     Track anchor, int anchorIndex) {
        List<Track> clean = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (tracks != null) {
            for (Track t : tracks) {
                if (t == null || t.id == null || t.id.isEmpty()) continue;
                if (seen.contains(t.id)) continue;
                seen.add(t.id);
                clean.add(t);
            }
        }
        String anchorId = anchor != null ? anchor.id : null;
        int index = anchorIndex;
        if (anchorId != null) {
            int found = -1;
            for (int i = 0; i < clean.size(); i++) {
                if (anchorId.equals(clean.get(i).id)) { found = i; break; }
            }
            if (found >= 0) index = found;
        }
        if (index < 0 || index >= clean.size()) index = clean.isEmpty() ? -1 : 0;
        return new PlaybackContext(type, contextId, clean, anchorId, index);
    }

    public PlaybackContextType type() {
        return type;
    }

    public String contextId() {
        return contextId;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public int size() {
        return tracks.size();
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /** Stable identity used to decide whether an active session still applies. */
    public String sourceKey() {
        return type.wire() + ":" + contextId;
    }

    public String anchorTrackId() {
        return anchorTrackId;
    }

    public int anchorIndex() {
        return anchorIndex;
    }

    public Track anchorTrack() {
        return anchorTrackId != null ? byId.get(anchorTrackId) : null;
    }

    public Track trackById(String id) {
        return id != null ? byId.get(id) : null;
    }

    /** The same context with a different track chosen as the starting point. */
    public PlaybackContext withAnchor(Track anchor) {
        return of(type, contextId, tracks, anchor, -1);
    }

    /** Context tracks in their natural listed order, starting after the anchor. */
    public List<Track> tracksAfterAnchor() {
        if (tracks.isEmpty()) return Collections.emptyList();
        int start = Math.max(0, Math.min(anchorIndex, tracks.size() - 1));
        return new ArrayList<>(tracks.subList(start, tracks.size()));
    }

    @Override
    public String toString() {
        return "PlaybackContext{" + sourceKey() + " tracks=" + tracks.size()
                + " anchor=" + anchorTrackId + "@" + anchorIndex + "}";
    }
}
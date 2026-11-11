package com.nightlight.app.playback;

import com.nightlight.app.domain.model.Track;

import java.util.List;

/**
 * Turns a {@link PlaybackStartIntent} into a {@link PlaybackContext}.
 *
 * <p>The important rule here is that a tap inside a list keeps the list. A
 * search result, album track or playlist row resolves to a collection context
 * anchored on the tapped track, so "next" continues through that collection
 * instead of wandering into the whole catalogue. Only a genuinely isolated play
 * — no collection supplied — resolves to {@link PlaybackContextType#SINGLE}.
 */
public final class PlaybackContexts {

    private PlaybackContexts() {
    }

    public static PlaybackContext from(PlaybackStartIntent intent) {
        if (intent == null || intent.track == null) {
            return PlaybackContext.of(PlaybackContextType.SINGLE, "", null, null, -1);
        }
        Track track = intent.track;
        List<Track> source = intent.sourceTracks;

        boolean hasCollection = false;
        if (source != null) {
            for (Track t : source) {
                if (t != null && t.id != null && !t.id.equals(track.id)) { hasCollection = true; break; }
            }
        }

        PlaybackContextType type = intent.contextType;
        if (type == null) type = PlaybackContextType.SINGLE;
        if (type != PlaybackContextType.SINGLE && !hasCollection) {
            type = PlaybackContextType.SINGLE;
        }

        if (type == PlaybackContextType.SINGLE) {
            return PlaybackContext.of(PlaybackContextType.SINGLE, contextId(intent, type),
                    java.util.Collections.singletonList(track), track, 0);
        }

        List<Track> tracks = ensureTrackPresent(source, track);
        return PlaybackContext.of(type, contextId(intent, type), tracks, track, intent.position);
    }

    /** A context for recommendations taking over from an exhausted one. */
    public static PlaybackContext autoplay(List<Track> recommendations, Track seed) {
        String id = "autoplay:" + (seed != null && seed.id != null ? seed.id : "seed");
        return PlaybackContext.of(PlaybackContextType.AUTOPLAY, id, recommendations, null, -1);
    }

    private static String contextId(PlaybackStartIntent intent, PlaybackContextType type) {
        if (intent.contextId != null && !intent.contextId.isEmpty()) return intent.contextId;
        if (type == PlaybackContextType.SEARCH) return "search";
        if (type == PlaybackContextType.SINGLE) {
            return intent.track != null && intent.track.id != null ? "single:" + intent.track.id : "single";
        }
        return type.wire();
    }

    /**
     * Returns the source list UNCHANGED in its natural order, appending the
     * tapped track only when the caller left it out.
     *
     * <p>Order matters: search results A..F with C tapped must keep C's real
     * position, because sequential playback continues forward from the
     * selection. Hoisting the tapped track to the front would replay A and B.
     */
    private static List<Track> ensureTrackPresent(List<Track> source, Track track) {
        java.util.List<Track> out = new java.util.ArrayList<>();
        if (source != null) {
            for (Track t : source) {
                if (t == null || t.id == null) continue;
                boolean dup = false;
                for (Track existing : out) {
                    if (t.id.equals(existing.id)) { dup = true; break; }
                }
                if (!dup) out.add(t);
            }
        }
        if (track != null && track.id != null) {
            boolean present = false;
            for (Track existing : out) {
                if (track.id.equals(existing.id)) { present = true; break; }
            }
            if (!present) out.add(track);
        }
        return out;
    }
}
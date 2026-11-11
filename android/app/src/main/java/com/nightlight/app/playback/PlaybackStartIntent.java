package com.nightlight.app.playback;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the user asked to play, before any context exists.
 *
 * <p>Every play action in the app builds one of these, which is what stops
 * "tapped a search result" from silently degrading into "play this one isolated
 * track and then wander off".
 */
public final class PlaybackStartIntent {

    public final Track track;
    public final PlaybackContextType contextType;
    public final String contextId;
    public final List<Track> sourceTracks;
    public final int position;
    public final Boolean requestedShuffle;

    private PlaybackStartIntent(Track track, PlaybackContextType contextType, String contextId,
                               List<Track> sourceTracks, int position, Boolean requestedShuffle) {
        this.track = track;
        this.contextType = contextType;
        this.contextId = contextId;
        this.sourceTracks = sourceTracks;
        this.position = position;
        this.requestedShuffle = requestedShuffle;
    }

    public static Builder of(Track track) {
        return new Builder(track);
    }

    /**
     * A track the user opened directly, with no collection to continue from.
     * Autoplay takes over as soon as this track ends.
     */
    public static PlaybackStartIntent single(Track track) {
        return new Builder(track).type(PlaybackContextType.SINGLE).build();
    }

    /**
     * Isolated playback of one track with no source collection behind it. Used
     * for a direct track action (a home card, a notification) where continuing
     * into a surrounding list would be wrong.
     */
    public static PlaybackStartIntent fromTrack(Track track) {
        return single(track);
    }

    /** A track tapped inside a list, where the rest of the list is the context. */
    public static PlaybackStartIntent fromCollection(Track track, PlaybackContextType type,
                                                     String contextId, List<Track> sourceTracks,
                                                     int position) {
        return new Builder(track).type(type).contextId(contextId)
                .sourceTracks(sourceTracks).position(position).build();
    }

    public static final class Builder {
        private final Track track;
        private PlaybackContextType type = PlaybackContextType.SINGLE;
        private String contextId;
        private List<Track> sourceTracks;
        private int position = -1;
        private Boolean requestedShuffle;

        private Builder(Track track) {
            this.track = track;
        }

        public Builder type(PlaybackContextType type) {
            if (type != null) this.type = type;
            return this;
        }

        public Builder contextId(String id) {
            this.contextId = id;
            return this;
        }

        public Builder sourceTracks(List<Track> tracks) {
            this.sourceTracks = tracks;
            return this;
        }

        public Builder position(int index) {
            this.position = index;
            return this;
        }

        public Builder shuffle(Boolean requested) {
            this.requestedShuffle = requested;
            return this;
        }

        public PlaybackStartIntent build() {
            List<Track> copy = new ArrayList<>();
            if (sourceTracks != null) {
                for (Track t : sourceTracks) {
                    if (t != null) copy.add(t);
                }
            }
            return new PlaybackStartIntent(track, type, contextId,
                    Collections.unmodifiableList(copy), position, requestedShuffle);
        }
    }
}
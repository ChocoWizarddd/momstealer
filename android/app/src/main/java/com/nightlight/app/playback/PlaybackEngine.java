package com.nightlight.app.playback;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The single queue pipeline every playback source goes through.
 *
 * <pre>
 * PlaybackStartIntent
 *   -> PlaybackContext      (what collection, which track)
 *   -> PlaybackSession      (the one fixed order + cursor)
 *   -> upcoming window      (what the player holds)
 *   -> refill from the SAME order when it runs low
 *   -> context exhausted -> Autoplay, or stop
 * </pre>
 *
 * <p>There is no playlist-only branch, no search-only branch and no separate
 * queue per surface. Recommendations are never mixed into the context order;
 * they only ever arrive as a follow-on Autoplay context once the context is
 * exhausted, so a recommendation can never evict or reorder context tracks.
 */
public final class PlaybackEngine {

    public static final int DEFAULT_WINDOW = 10;

    private PlaybackEngine() {
    }

    public static PlaybackSession start(PlaybackStartIntent intent, boolean shuffleOn,
                                        boolean smartShuffle, List<String> recentlyPlayedIds) {
        PlaybackContext context = PlaybackContexts.from(intent);
        return start(context, shuffleOn, smartShuffle, recentlyPlayedIds);
    }

    public static PlaybackSession start(PlaybackContext context, boolean shuffleOn,
                                        boolean smartShuffle, List<String> recentlyPlayedIds) {
        if (context == null || context.isEmpty()) {
            return PlaybackSession.sequential(
                    PlaybackContext.of(PlaybackContextType.SINGLE, "empty", null, null, -1));
        }
        if (!shuffleOn) return PlaybackSession.sequential(context);
        return PlaybackSession.create(context, smartShuffle, PlaybackSession.randomSeed(), recentlyPlayedIds);
    }

    /**
     * Rebuilds the order for an existing context after the user toggles
     * shuffle, keeping the track that is currently playing as the starting
     * point.
     *
     * <p>Rebuilds from the FULL context rather than from whatever window the
     * player currently holds, so toggling never truncates the collection.
     */
    public static PlaybackSession restartKeepingTrack(PlaybackContext context, Track keepPlaying,
                                                      boolean shuffleOn, boolean smartShuffle,
                                                      List<String> recentlyPlayedIds) {
        if (context == null || context.isEmpty()) {
            return PlaybackSession.sequential(
                    PlaybackContext.of(PlaybackContextType.SINGLE, "empty", null, null, -1));
        }
        PlaybackContext anchored = context.withAnchor(keepPlaying);
        return start(anchored, shuffleOn, smartShuffle, recentlyPlayedIds);
    }

    /**
     * Ids to append so the player keeps {@code windowSize} upcoming context
     * tracks. Reads the session's existing order only; it can never reshuffle,
     * rewind or reintroduce a played track.
     */
    public static List<String> refill(PlaybackSession session, Collection<String> queuedIds, int windowSize) {
        if (session == null || windowSize <= 0) return Collections.emptyList();

        java.util.LinkedHashSet<String> queued = new java.util.LinkedHashSet<>();
        if (queuedIds != null) {
            for (String id : queuedIds) {
                if (id != null && !id.isEmpty()) queued.add(id);
            }
        }

        int have = 0;
        for (String id : queued) {
            if (!session.playedIds().contains(id)) have++;
        }
        int need = windowSize - have;
        if (need <= 0) return Collections.emptyList();

        List<String> out = new ArrayList<>(need);
        for (String id : session.remaining()) {
            if (need <= 0) break;
            if (session.playedIds().contains(id)) continue;
            if (queued.contains(id)) continue;
            out.add(id);
            queued.add(id);
            need--;
        }
        return out;
    }

    /**
     * What should happen when the current context has no tracks left.
     *
     * @param autoplayEnabled whether the user wants playback to continue
     * @param recommendations recommendation candidates, may be empty
     * @return a fresh Autoplay session, or {@code null} to stop
     */
    public static PlaybackSession onExhausted(PlaybackSession session, boolean autoplayEnabled,
                                               List<Track> recommendations, boolean shuffleRecommendations) {
        if (session == null) return null;
        if (!autoplayEnabled) return null;
        if (!session.context().type().handsOverToAutoplay()) return null;
        Track seed = session.currentId() != null ? session.trackById(session.currentId()) : null;
        PlaybackContext autoplay = PlaybackContexts.autoplay(recommendations, seed);
        if (autoplay.isEmpty()) return null;
        if (!shuffleRecommendations) return PlaybackSession.sequential(autoplay);
        return PlaybackSession.create(autoplay, true, PlaybackSession.randomSeed(), null);
    }

    /** Tracks after the cursor, resolved to objects for the player. */
    public static List<Track> upcomingTracks(PlaybackSession session, int size) {
        List<Track> out = new ArrayList<>();
        if (session == null) return out;
        for (String id : session.upcoming(size)) {
            Track t = session.trackById(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Ids queued from recommendations, which live outside the context order. */
    public static List<String> recommendationLayer(List<String> contextOrder, List<String> recommendationIds) {
        List<String> out = new ArrayList<>();
        if (recommendationIds == null) return out;
        Collection<String> context = contextOrder != null ? contextOrder : Collections.<String>emptyList();
        for (String id : recommendationIds) {
            if (id != null && !id.isEmpty() && !context.contains(id)) out.add(id);
        }
        return out;
    }
}
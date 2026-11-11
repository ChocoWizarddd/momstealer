package com.nightlight.app.player;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.playback.PlaybackContext;
import com.nightlight.app.playback.PlaybackContexts;
import com.nightlight.app.playback.PlaybackEngine;
import com.nightlight.app.playback.PlaybackSession;
import com.nightlight.app.playback.PlaybackStartIntent;
import com.nightlight.app.shuffle.ManualQueue;
import com.nightlight.app.shuffle.QueueManager;
import com.nightlight.app.shuffle.ShuffleOrderFactory;
import com.nightlight.app.smartshuffle.SmartShuffleEngine;
import com.nightlight.app.util.MoodPrefs;
import com.nightlight.app.util.ShufflePrefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single source of truth for playback state. UI never reads ExoPlayer
 * directly: it observes {@link PlaybackSnapshot} delivered through
 * {@link Listener#onPlaybackChanged}. Everything here runs on the main thread.
 *
 * Smart Shuffle uses an authoritative curated queue: Media3 shuffle is
 * disabled so our order is respected, and a generation counter protects
 * against stale radio responses contaminating the active queue.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackManager {

    public interface Listener {
        @MainThread
        void onPlaybackChanged(PlaybackSnapshot snapshot);
    }

    /** Called when a new track actually starts playing (for recently-played). */
    public interface TrackStartedListener {
        @MainThread
        void onTrackStarted(Track track);
    }

    private static volatile PlaybackManager instance;

    private final Context app;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaController controller;
    private volatile PlaybackSnapshot snapshot = PlaybackSnapshot.EMPTY;
    private TrackStartedListener trackStartedListener;
    private String recordedTrackId;
    private boolean tickerRunning;

    /** Guards against stacking radio requests (auto-next / shuffle top-up). */
    private boolean radioBusy;

    /**
     * Monotonically increasing counter. Every time Smart Shuffle is activated
     * or the user manually changes tracks, this is bumped so in-flight radio
     * responses for older seeds are discarded instead of mutating the queue.
     */
    private final AtomicInteger smartGeneration = new AtomicInteger(0);

    /** Seed track id of the most recent radio request (for validation). */
    private String lastRadioSeedId;

    /**
     * Anchor id of the session-initial radio request. That request is
     * seeded on the track that is about to start, so the player's own
     * start onto the anchor (onMediaItemTransition + handleTrackStart)
     * must not invalidate it: the first landing on this id is exempt
     * from the generation bump (see {@link #noteTrackTransition}).
     */
    private String pendingRadioAnchorId;

    /**
     * Canonical playback state. The context says which collection playback is
     * running inside; the session holds the ONE fixed order for that context
     * plus the cursor into it. Playback only ever moves the cursor — the order
     * is never regenerated here.
     */
    private PlaybackContext activeContext;
    private PlaybackSession activeSession;

    /**
     * Owns queue composition. The session owns the order; this decides how much
     * of it the player holds and where manual items interleave. Having it as a
     * separate object is what lets the refill/race/next rules be unit tested.
     */
    private final QueueManager queueManager = new QueueManager(QUEUE_WINDOW);

    /** Seed id of an in-flight Autoplay hand-over, cleared once consumed. */
    private String autoplayPendingSeedId;

    /** Upcoming context tracks the player holds at once. */
    private static final int QUEUE_WINDOW = 10;

    /** Whether Autoplay takes over once the active context runs out. */
    private boolean autoplayEnabled = true;

    public PlaybackContext activeContext() {
        return activeContext;
    }

    public PlaybackSession activeSession() {
        return activeSession;
    }

    public ManualQueue manualQueue() {
        return queueManager.manualQueue();
    }

    public void setAutoplayEnabled(boolean enabled) {
        this.autoplayEnabled = enabled;
    }

    /** Media id of the last item a transition was accepted for (double-advance guard). */
    private String lastTransitionId;

    /**
     * This session's recent tracks (feeds the Smart Shuffle engine).
     * Bounded at {@link #MAX_SESSION_RECENT}.
     */
    private final List<Track> sessionRecent = new ArrayList<>();
    private static final int MAX_SESSION_RECENT = 24;

    /**
     * Artists the listener skipped this session (weak negative signal).
     * Bounded at {@link #MAX_SESSION_SKIPS}.
     */
    private final Set<String> sessionSkips = new HashSet<>();
    private static final int MAX_SESSION_SKIPS = 24;

    private long currentTrackStartedAt;

    /**
     * Listened milliseconds accumulated in this process, measured from the
     * playback ticker so paused time is never counted. Combined with the
     * persisted taste profile by {@link #getTotalPlaytimeMs()}.
     */
    private long sessionPlaytimeMs;
    private long lastTickAt;

    /**
     * Play-threshold reporter for NightLight-owned popularity: a track counts
     * as "played" once playback crosses min(30s, 50% of duration). Reported
     * once per track load; the server re-validates and dedupes.
     */
    private static final long PLAY_THRESHOLD_MS = 30_000L;
    private String playReportedTrackId;

    /**
     * User NEXT taps that have not yet been reflected by an item transition.
     * Media3 can coalesce back-to-back relative seeks when a previous skip is
     * still buffering, which used to silently drop rapid taps; the backlog is
     * drained one transition at a time so every tap still lands.
     */
    private int pendingNext;

    /** Retry watchdog for a skip that never manifested as a transition. */
    private static final long NEXT_PUSH_RETRY_MS = 1200;
    private long nextPushGen;
    private final Runnable nextPushWatchdog = new Runnable() {
        @Override
        public void run() {
            pushNext();
        }
    };

    /** Fixed tick cadence (balanced profile, 500ms). */
    private static final long TICKER_MS = 500L;
    /** Fixed top-up threshold (balanced profile, 6 remaining). */
    private static final int TOPUP_THRESHOLD = 6;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            publish(false);
            maybeReportPlayThreshold();
            accumulatePlaytime();
            if (snapshot.isPlaying && tickerRunning) {
                main.postDelayed(this, TICKER_MS);
            }
        }
    };

    /**
     * Credits wall-clock time to the session total, but only while audio is
     * actually rolling. The ticker itself stops scheduling once playback
     * pauses, and each tick is clamped so a long stall (GC, doze, a hung
     * decoder) cannot inflate the total by minutes at once.
     */
    private void accumulatePlaytime() {
        long now = System.currentTimeMillis();
        if (lastTickAt > 0 && snapshot.isPlaying) {
            long delta = now - lastTickAt;
            if (delta > 0) {
                sessionPlaytimeMs += Math.min(delta, TICKER_MS * 2);
            }
        }
        lastTickAt = now;
    }

    private long tickerIntervalMs() {
        return TICKER_MS;
    }

    public static PlaybackManager get(Context context) {
        if (instance == null) {
            synchronized (PlaybackManager.class) {
                if (instance == null) {
                    instance = new PlaybackManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static final String TAG = "NightLight";

    private PlaybackManager(Context context) {
        this.app = context;
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(app, new ComponentName(app, PlaybackService.class));
        MediaController.Builder builder = new MediaController.Builder(app, token);
        ListenableFuture<MediaController> future = builder.buildAsync();
        future.addListener(() -> {
            try {
                MediaController c = future.get();
                if (c == null) {
                    main.postDelayed(PlaybackManager.this::connect, 1500);
                    return;
                }
                onConnected(c);
            } catch (Exception e) {
                main.postDelayed(PlaybackManager.this::connect, 1500);
            }
        }, main::post);
    }

    private void onConnected(MediaController c) {
        if (controller != null) {
            controller.release();
        }
        controller = c;
        Player.Listener playerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                handleTrackStart();
                publish(true);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                handleTrackStart();
                /**
         * STATE_ENDED means the player ran out of its window. Auto-advance must
         * therefore continue the SAME session order, top the window up from it,
         * and only hand over to recommendations once the context itself is
         * exhausted.
         */
                if (playbackState == Player.STATE_ENDED) {
                    if (activeSession != null && controller != null) {
                        if (!activeSession.isExhausted()) {
                            // The window ran dry mid-order. Top it up from the same
                            // session and start playing at the index the refill
                            // reported — NOT index 0, which would replay the window.
                            int firstNew = refillFromSession();
                            if (firstNew >= 0) {
                                controller.seekTo(firstNew, 0L);
                                controller.prepare();
                                controller.play();
                                publish(true);
                                return;
                            }
                        }
                        if (autoplayEnabled && startAutoplayFromSeed()) {
                            publish(true);
                            return;
                        }
                    }
                    // No session owns this queue (legacy radio path) — continue
                    // fetching recommendations so playback never dead-ends.
                    radioContinue();
                }
                publish(true);
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                // Race hardening: when the item actually changes (manual next,
                // auto-advance, seekToIndex), bump the generation so in-flight
                // radio responses for the previous seed can never mutate the
                // queue around the new track — even while still buffering.
                // The session's own start onto its anchor is exempt (see
                // noteTrackTransition) so the session-initial radio survives.
                noteTrackTransition(mediaItem != null ? mediaItem.mediaId : null);
                // Consume one pending NEXT: an item transition just occurred, so
                // one user skip has manifested. Drain the rest shortly after,
                // spacing the seeks so Media3 applies every one of them.
                if (pendingNext > 0) {
                    pendingNext--;
                    nextPushGen++;
                    main.removeCallbacks(nextPushWatchdog);
                    if (pendingNext > 0) {
                        main.postDelayed(nextPushWatchdog, 300);
                    }
                }
                recordedTrackId = null;
                handleTrackStart();
                checkProactiveTopUp();
                publish(true);
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                Log.i(TAG, "repeat mode -> " + repeatMode);
                publish(true);
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                Log.i(TAG, "shuffle -> " + shuffleModeEnabled);
                publish(true);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                publish(true);
            }

            @Override
            public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                publish(true);
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                publish(true);
            }
        };
        controller.addListener(playerListener);
        // Restore the persisted shuffle preference (Smart by default).
        applyShuffleMode(ShufflePrefs.mode(app));
        publish(true);
    }

    // ---- Registration ----

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(snapshot);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setTrackStartedListener(TrackStartedListener listener) {
        this.trackStartedListener = listener;
    }

    public PlaybackSnapshot getSnapshot() {
        return snapshot;
    }

    public boolean isConnected() {
        return controller != null;
    }

    // ---- Actions ----

    /**
     * The one entry point every surface uses to start playback.
     *
     * <p>Resolves the playback context, builds the single order for it — shuffled
     * once and anchored on the track the user tapped, or sequential from there —
     * and hands the player a window into that order. Which surface the track
     * came from is carried by the intent, so search, album, artist, playlist,
     * liked and single-track playback all share one code path instead of each
     * growing its own queue behaviour.
     */
    public void playFromIntent(PlaybackStartIntent intent) {
        if (controller == null || intent == null || intent.track == null) {
            return;
        }
        String mode = ShufflePrefs.mode(app);
        boolean shuffleOn = mode != null && !ShufflePrefs.OFF.equals(mode);
        boolean smart = ShufflePrefs.SMART.equals(mode);

        PlaybackContext context = PlaybackContexts.from(intent);
        activeContext = context;
        activeSession = PlaybackEngine.start(context, shuffleOn, smart, recentTrackIds());
        queueManager.manualQueue().clear();
        Log.i(TAG, "playback context -> " + activeSession.context()
                + " shuffle=" + shuffleOn + " smart=" + smart);
        if (smart) {
            // Diagnostics from the canonical multi-candidate shuffle:
            // proves on-device that the scored-permutation order ran.
            Log.i(TAG, "smart order -> pool=" + ShuffleOrderFactory.lastPoolSize()
                    + " candidates=" + ShuffleOrderFactory.lastCandidateCount()
                    + " bestScore=" + ShuffleOrderFactory.lastBestScore());
        }

        loadSessionWindow(true);
    }

    /** Recent track ids, newest last, used as a freshness penalty for Smart. */
    private List<String> recentTrackIds() {
        List<String> ids = new ArrayList<>();
        int from = Math.max(0, sessionRecent.size() - 15);
        for (int i = from; i < sessionRecent.size(); i++) {
            Track t = sessionRecent.get(i);
            if (t != null && t.id != null) ids.add(t.id);
        }
        return ids;
    }

    /**
     * Keeps the session cursor pointing at the track that is actually playing.
     *
     * <p>The cursor is advanced HERE and nowhere else. Tying it to track start
     * (guarded by track identity, so a re-render or a duplicate callback cannot
     * double-advance) means queue refills and UI updates never move it, which is
     * what keeps the order stable.
     */
    private void syncSessionCursorTo(String playingId) {
        if (activeSession == null || playingId == null) return;
        if (playingId.equals(activeSession.currentId())) return;
        // O(1) and safe in both directions. Manual "Play Next" items are not in
        // the order, so they simply do not match and the cursor is left alone.
        activeSession.syncCursorTo(playingId);
    }

    /**
     * Rebuilds the player's queue as a window into the active session order:
     * the playing track, then any manual "Play Next" items, then the upcoming
     * context tracks. Reads the order only — never reshuffles it.
     */
    private void loadSessionWindow(boolean startPlaying) {
        if (controller == null || activeSession == null) {
            return;
        }
        queueManager.beginSession(activeSession);
        List<Track> window = startPlaying
                ? queueManager.startWindow()
                : queueManager.resumeWindow();
        applyWindow(window, startPlaying);
    }

    private void applyWindow(List<Track> window, boolean startPlaying) {
        if (controller == null || window == null || window.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>(window.size());
        for (Track t : window) {
            if (isPlayable(t)) items.add(toMediaItem(t));
        }
        if (items.isEmpty()) {
            return;
        }
        controller.setMediaItems(items, 0, 0L);
        controller.prepare();
        if (startPlaying) controller.play();
    }

    /**
     * Keeps the player topped up from the SAME session order. Idempotent: a
     * queue that is already full adds nothing, so a burst of QUEUE_LOW events
     * produces exactly one logical refill.
     *
     * @return index of the first appended item, or -1 if nothing was added
     */
    public int refillFromSession() {
        if (controller == null || activeSession == null) return -1;
        int currentIndex = controller.getCurrentMediaItemIndex();
        int upcoming = 0;
        for (int i = currentIndex + 1; i < controller.getMediaItemCount(); i++) {
            Track t = trackAt(i);
            if (t == null || t.id == null) continue;
            if (activeSession.trackById(t.id) != null) upcoming++;
        }
        QueueManager.Refill refill = queueManager.refill(upcoming, controller.getMediaItemCount());
        if (!refill.addedAnything()) {
            Log.d(TAG, "[REFILL] trigger=manual required=0 added=0 requestId=" + refill.requestId());
            return -1;
        }
        List<MediaItem> items = new ArrayList<>(refill.addedIds().size());
        for (String id : refill.addedIds()) {
            Track t = activeSession.trackById(id);
            if (isPlayable(t)) items.add(toMediaItem(t));
        }
        if (items.isEmpty()) return -1;
        int firstNew = controller.getMediaItemCount();
        controller.addMediaItems(items);
        Log.d(TAG, "[REFILL] trigger=manual required=" + (QUEUE_WINDOW - upcoming)
                + " added=" + items.size() + " requestId=" + refill.requestId()
                + " at=" + firstNew);
        return firstNew;
    }

    private boolean isPlayable(Track t) {
        return t != null && t.streamUrl != null && !t.streamUrl.isEmpty();
    }

    /** The track at a player queue position, or null when unknown. */
    private Track trackAt(int index) {
        if (controller == null || index < 0 || index >= controller.getMediaItemCount()) {
            return null;
        }
        MediaItem item = controller.getMediaItemAt(index);
        if (item.localConfiguration != null && item.localConfiguration.tag instanceof Track) {
            return (Track) item.localConfiguration.tag;
        }
        return trackFromMetadata(item.mediaMetadata, item.mediaId);
    }

    /**
     * Resolves a track id against the active session first, then the player
     * queue. Used for manual queue entries that may not be in the context.
     */
    private Track findTrackById(String id) {
        if (id == null) return null;
        if (activeSession != null) {
            Track t = activeSession.trackById(id);
            if (t != null) return t;
        }
        if (controller != null) {
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                Track t = trackAt(i);
                if (t != null && id.equals(t.id)) return t;
            }
        }
        return null;
    }

    /** Plays a resolved list of tracks (must have stream URLs). */
    public void playTracks(List<Track> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        int start = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        Track selected = tracks.get(start);
        playFromIntent(PlaybackStartIntent.fromCollection(selected,
                PlaybackContextTypeFor(tracks), "", tracks, start));
    }

    /**
     * A bare list with no declared surface still deserves a real collection
     * context, so the queue continues through the list instead of stopping.
     */
    private static com.nightlight.app.playback.PlaybackContextType PlaybackContextTypeFor(List<Track> tracks) {
        return tracks.size() > 1
                ? com.nightlight.app.playback.PlaybackContextType.LIBRARY
                : com.nightlight.app.playback.PlaybackContextType.SINGLE;
    }

    /** Appends tracks to the end of the current queue. */
    public void addToQueue(List<Track> tracks) {
        if (controller == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        // Deduplicate by stable track ID before insertion — never add the same
        // track twice to the queue.
        Set<String> existingIds = existingQueueIds();
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            if (track.streamUrl != null && !existingIds.contains(track.id)) {
                existingIds.add(track.id);
                items.add(toMediaItem(track));
            }
        }
        if (!items.isEmpty()) {
            controller.addMediaItems(items);
        }
        // Appending does NOT touch the session order. Regenerating here is what
        // used to make "Next" produce a different sequence after adding a track.
    }

    /**
     * Strips what must never play from a fresh radio batch: the seed
     * itself, artists the user skipped this session, tracks already in
     * the player queue and anything without a stream URL. The
     * provider's relevance order is preserved — the top suggestion
     * stays first.
     */
    private List<Track> filterRadioBatch(Track seed, List<Track> tracks) {
        Set<String> inQueue = existingQueueIds();
        List<Track> out = new ArrayList<>();
        for (Track t : tracks) {
            if (t == null || t.id == null || t.streamUrl == null || t.streamUrl.isEmpty()) {
                continue;
            }
            if (seed != null && seed.id != null && seed.id.equals(t.id)) {
                continue;
            }
            if (inQueue.contains(t.id)) {
                continue;
            }
            if (isSkippedArtist(t.artists)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** True when any of the track's artists was skipped this session. */
    private boolean isSkippedArtist(String artists) {
        if (artists == null || artists.isEmpty() || sessionSkips.isEmpty()) {
            return false;
        }
        for (String part : artists.split(",")) {
            String norm = part.trim().toLowerCase();
            if (norm.isEmpty()) {
                continue;
            }
            for (String skipped : sessionSkips) {
                if (skipped == null) {
                    continue;
                }
                for (String sPart : skipped.split(",")) {
                    if (norm.equals(sPart.trim().toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Inserts a radio batch directly behind the playing track (behind
     * any manual "Play Next" items) — the session-initial radio lands
     * here so the very next track is the top radio suggestion, not
     * another search result. The session order itself is untouched:
     * these tracks are context-free additions, like manual "Play Next"
     * items, and playback resumes the session order once the batch is
     * consumed.
     */
    private void insertAfterCurrent(List<Track> tracks) {
        if (controller == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        int manual = queueManager.manualQueue().size();
        int index = controller.getCurrentMediaItemIndex() + 1 + manual;
        List<MediaItem> items = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            if (isPlayable(t)) {
                items.add(toMediaItem(t));
            }
        }
        if (!items.isEmpty()) {
            controller.addMediaItems(index, items);
        }
    }

    /** Inserts a track to play right after the current one. */
    /**
     * "Play Next": a one-off insert directly after the current track.
     *
     * <p>Deliberately kept OUT of the shuffle session — the context order must
     * stay exactly what it was when playback started, or repeated NEXT would no
     * longer follow a stable order. The manual queue is tracked separately so a
     * rebuilt window can restore these items.
     */
    public void playNext(Track track) {
        if (controller == null || track == null || track.streamUrl == null) {
            return;
        }
        queueManager.playNext(track);
        int index = controller.getCurrentMediaItemIndex() + 1;
        controller.addMediaItem(index, toMediaItem(track));
    }

    public void togglePlayPause() {
        if (controller == null) {
            return;
        }
        // Optimistic UI: flip the icon instantly so the button never feels
        // late — Media3 IPC + buffering delay the real event by 100-400ms.
        boolean willPlay = !controller.isPlaying();
        if (snapshot != null && snapshot.current != null && snapshot.isPlaying != willPlay) {
            PlaybackSnapshot optimistic = new PlaybackSnapshot(
                    snapshot.current, willPlay, willPlay, snapshot.position,
                    snapshot.duration, snapshot.hasQueue, snapshot.repeatMode,
                    snapshot.shuffle, snapshot.error, snapshot.queueSize,
                    snapshot.currentIndex, snapshot.connected, snapshot.audioLine);
            snapshot = optimistic;
            for (Listener l : listeners) {
                l.onPlaybackChanged(optimistic);
            }
        }
        if (controller.isPlaying()) {
            controller.pause();
        } else {
            if (controller.getCurrentMediaItem() != null) {
                controller.play();
            }
        }
    }

    public void pause() {
        if (controller != null) {
            controller.pause();
        }
    }

    public void next() {
        if (controller == null) {
            return;
        }
        // No time-debounce: every tap enqueues exactly one advance and the
        // pendingNext backlog + watchdog drain them in order, so rapid taps
        // all land (a 350ms swallow window used to eat the second tap and
        // read as "next does nothing"). A single tap still advances exactly
        // one track because each backlog unit consumes one transition.
        // Skipping a track within its first 30s is a weak negative signal for
        // the current artist — Smart Shuffle lowers their ranking this session.
        if (controller.isPlaying()
                && System.currentTimeMillis() - currentTrackStartedAt < 30_000) {
            Track t = currentTrack();
            if (t != null && t.artists != null && !t.artists.isEmpty()) {
                sessionSkips.add(t.artists);
                if (sessionSkips.size() > MAX_SESSION_SKIPS) {
                    sessionSkips.clear();
                }
            }
        }
        /**
         * Canonical path: the session owns the order, so NEXT just walks the
         * cursor forward. The player holds a window into that order, which is
         * topped up from the SAME session — never reshuffled — so repeated NEXT
         * always produces the same sequence.
         */
        if (activeSession != null) {
            if (activeSession.isExhausted()) {
                if (autoplayEnabled && startAutoplayFromSeed()) {
                    return;
                }
            } else if (controller.hasNextMediaItem()) {
                // Top the window up from the SAME order first so the track we are
                // about to move onto is definitely buffered. Refill no longer
                // moves the cursor — syncSessionCursorTo does that once the new
                // track actually starts.
                refillFromSession();
                pendingNext++;
                controller.seekToNextMediaItem();
                controller.play();
                return;
            } else {
                // Player drifted out of step with the session order: rebuild the
                // window from the session and start it at the current track.
                loadSessionWindow(false);
                controller.seekTo(0, 0L);
                controller.play();
                return;
            }
        }
        pendingNext++;
        pushNext();
    }

    /**
     * Hands the exhausted context over to recommendations.
     *
     * <p>Recommendations are fetched asynchronously, so this only marks the
     * hand-over as pending and kicks the radio request; the response installs
     * the new AUTOPLAY context (see {@code fetchRadio}'s success path). Returns
     * false when there is nothing to hand over to, so the caller stops cleanly
     * instead of silently doing nothing.
     */
    private boolean startAutoplayFromSeed() {
        if (controller == null || radioBusy) return false;
        Track seed = currentTrack();
        if (seed == null && sessionRecent != null && !sessionRecent.isEmpty()) {
            seed = sessionRecent.get(sessionRecent.size() - 1);
        }
        if (seed == null || seed.id == null) return false;
        autoplayPendingSeedId = seed.id;
        fetchRadio(seed, 30, false, smartGeneration.incrementAndGet(), 0);
        return true;
    }

    /**
     * Builds the AUTOPLAY context for a recommendation batch and plays it from
     * the top. Called once per hand-over, never per track, so the hand-over
     * order is stable for the life of that context.
     */
    private void installAutoplayContext(List<Track> recommendations, Track seed) {
        PlaybackContext autoplay = PlaybackContexts.autoplay(recommendations, seed);
        if (autoplay.isEmpty()) return;
        activeContext = autoplay;
        activeSession = PlaybackSession.create(autoplay, isSmartShuffleOn(),
                PlaybackSession.randomSeed(), null);
        if (activeSession == null || activeSession.isExhausted()) return;
        Log.i(TAG, "autoplay hand-over: " + recommendations.size() + " recommendations");
        loadSessionWindow(true);
    }

    private boolean isSmartShuffleOn() {
        return ShufflePrefs.SMART.equals(ShufflePrefs.mode(app));
    }

    /**
     * Advances the queue for each pending NEXT. The debounce in {@link #next()}
     * still guarantees one tap == one track; this loop only makes sure a skip is
     * never lost when Media3 coalesces a seek issued while a previous skip is
     * still buffering/transitioning.
     */
    private void pushNext() {
        if (controller == null || pendingNext <= 0) {
            return;
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
        } else if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            controller.seekToDefaultPosition(0);
        } else {
            // Nothing after the current item and repeat is off: clear the
            // backlog so the retry watchdog does not spin forever.
            pendingNext = 0;
            return;
        }
        // Watchdog: if the seek was coalesced away while another transition was
        // in flight, re-push until an item actually changes.
        nextPushGen++;
        main.removeCallbacks(nextPushWatchdog);
        main.postDelayed(nextPushWatchdog, NEXT_PUSH_RETRY_MS);
    }

    public void previous() {
        if (controller == null) {
            return;
        }
        if (controller.getCurrentPosition() > 3_000) {
            controller.seekTo(0);
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem();
        } else {
            // First track at its start: restart it. Previously this fell
            // through silently — a dead button.
            controller.seekTo(0);
        }
    }

    public void seekTo(long positionMs) {
        if (controller != null) {
            controller.seekTo(positionMs);
        }
    }

    /**
     * Jumps to a queue index (used by the queue sheet).
     *
     * <p>Selecting from the queue sheet re-anchors the session on that track so
     * subsequent NEXT continues the SAME order from there. It does not rebuild
     * the order, and it does not lose the tracks that came before.
     */
    public void seekToIndex(int index) {
        if (controller == null) {
            return;
        }
        Track selected = trackAt(index);
        controller.seekTo(index, 0L);
        if (selected != null && activeContext != null && !activeContext.isEmpty()) {
            activeSession = PlaybackEngine.restartKeepingTrack(activeContext, selected,
                    !ShufflePrefs.OFF.equals(ShufflePrefs.mode(app)),
                    ShufflePrefs.SMART.equals(ShufflePrefs.mode(app)), recentTrackIds());
            loadSessionWindow(true);
        }
    }

    /** Cycles repeat: OFF -> ALL -> ONE -> OFF. */
    public int cycleRepeat() {
        if (controller == null) {
            return 0;
        }
        int next = (controller.getRepeatMode() + 1) % 3;
        controller.setRepeatMode(next);
        publish(true);
        return next;
    }

    public void setRepeat(int mode) {
        if (controller != null) {
            controller.setRepeatMode(mode);
        }
    }

    /**
     * Single shuffle: Smart is the only mode, so this no longer
     * cycles OFF -> SMART -> NORMAL. It re-asserts Smart — which
     * also refreshes the radio-first queue around the current
     * track — so the Now Playing shuffle button stays meaningful.
     *
     * @return the active mode, always {@code smart}
     */
    public String toggleShuffle() {
        ShufflePrefs.setMode(app, ShufflePrefs.SMART);
        applyShuffleMode(ShufflePrefs.SMART);
        return ShufflePrefs.SMART;
    }

    /**
     * Applies a stored shuffle preference (used on startup / settings change).
     *
     * NORMAL shuffle uses a Fisher-Yates permutation engine — Media3 shuffle
     * is always OFF so our permutation order is respected.
     *
     * Smart Shuffle uses a curated contextual queue.
     *
     * Off = sequential, no top-ups.
     */
    public void applyShuffleMode(String mode) {
        if (controller == null) {
            return;
        }
        // Media3 shuffle is always disabled — we control the order.
        controller.setShuffleModeEnabled(false);

        /**
         * Rebuilds the order for the active context when shuffle is toggled.
         *
         * <p>Toggling is one of the few legitimate reasons to build a new order.
         * It must rebuild from the FULL context, not from the player queue —
         * the queue is only a 10-track window into the order, so permuting it
         * would permanently lose the rest of the context.
         */
        if (activeContext != null && !activeContext.isEmpty() && controller.getMediaItemCount() > 0) {
            Track keep = currentTrack();
            if (keep != null) {
                int gen = smartGeneration.incrementAndGet();
                activeSession = PlaybackEngine.restartKeepingTrack(activeContext,
                        keep, !ShufflePrefs.OFF.equals(mode), ShufflePrefs.SMART.equals(mode),
                        recentTrackIds());
                queueManager.manualQueue().clear();
                loadSessionWindow(true);
                publish(true);
                if (ShufflePrefs.SMART.equals(mode)) {
                    // Same canonical multi-candidate shuffle as a fresh
                    // playFromIntent — logged so the rebuild path is
                    // provable on-device too.
                    Log.i(TAG, "smart order -> pool=" + ShuffleOrderFactory.lastPoolSize()
                            + " candidates=" + ShuffleOrderFactory.lastCandidateCount()
                            + " bestScore=" + ShuffleOrderFactory.lastBestScore());
                    // Radio-first: related songs land right behind the
                    // kept track, exactly like a fresh smart session.
                    pendingRadioAnchorId = keep.id;
                    fetchRadio(keep, 24, true, gen, 0, true);
                }
                return;
            }
        }

        if (ShufflePrefs.SMART.equals(mode)) {
            // Entering Smart mode: bump generation to invalidate old requests.
            int gen = smartGeneration.incrementAndGet();
            // Immediately curate the existing queue so the toggle is visible.
            curateSmartQueue(gen);
            // Fetch radio candidates in the background.
            Track seed = currentTrack();
            if (seed != null && controller.getMediaItemCount() > 0) {
                pendingRadioAnchorId = seed.id;
                fetchRadio(seed, 24, true, gen, 0, true);
            }
        } else {
            // NORMAL and OFF are both served by the session order.
            smartGeneration.incrementAndGet();
        }
        publish(true);
    }

    /**
     * Opens a fresh Smart Shuffle session around the track that is playing
     * now. Unlike {@link #applyShuffleMode(String)} this is an explicit
     * "start a new session" action (tapping a track in Smart mode), so it
     * always re-seeds the recommendation pool from the current track and
     * discards any in-flight radio response from the previous seed.
     *
     * <p>The persisted shuffle preference is left untouched — callers decide
     * whether Smart mode should stick.
     */
    public void startSmartSession() {
        if (controller == null) {
            return;
        }
        Track seed = currentTrack();
        int gen = smartGeneration.incrementAndGet();
        lastRadioSeedId = null;
        if (seed == null || controller.getMediaItemCount() == 0) {
            publish(true);
            return;
        }
        // Radio-first: the batch lands directly behind this track, so
        // the player's own start onto the anchor must not discard it
        // (see noteTrackTransition).
        pendingRadioAnchorId = seed.id;
        fetchRadio(seed, 24, true, gen, 0, true);
        publish(true);
    }

    /**
     * Immediately reorders the current Media3 queue using SmartShuffleEngine
     * instead of a dumb random shuffle. The currently playing track stays at
     * index 0 (preserving playback), and all other tracks are scored and
     * re-ordered with context, mood, diversity, and skip penalties.
     *
     * This makes the Smart Shuffle toggle visually instant — the user sees
     * the queue change immediately.
     */
    private void curateSmartQueue(int generation) {
        if (controller == null || controller.getMediaItemCount() < 2) {
            return;
        }
        long position = controller.getCurrentPosition();
        boolean playing = controller.isPlaying();

        // Resolve the seed from the CURRENT item first, then collect the rest of
        // the queue. The seed is removed by IDENTITY (object or stable id) —
        // never by a re-derived index, which null-tag items would shift onto the
        // wrong song.
        Track currentTagged = currentTrack();
        MediaItem currentItem = controller.getCurrentMediaItem();
        Track seed = currentTagged;
        if (seed == null && currentItem != null) {
            seed = trackFromMetadata(currentItem.mediaMetadata, currentItem.mediaId);
        }
        if (seed == null) {
            return;
        }
        List<Track> allTracks = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            Track t = item.localConfiguration != null ? (Track) item.localConfiguration.tag : null;
            if (t == null) {
                t = trackFromMetadata(item.mediaMetadata, item.mediaId);
            }
            if (t == null) {
                continue;
            }
            if (t == currentTagged || t.id.equals(seed.id)) {
                continue; // seed itself is never re-queued
            }
            allTracks.add(t);
        }
        if (allTracks.isEmpty()) {
            return;
        }

        // Run remaining tracks through SmartShuffleEngine for intelligent ordering.
        NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
        SmartShuffleEngine engine = new SmartShuffleEngine();
        LibraryRepository library = nightLight.getLibraryRepository();
        List<Track> curated = engine.generateQueue(
                seed, allTracks, new ArrayList<>(sessionRecent),
                com.nightlight.app.util.AccountPrefs.effectiveMood(app.getApplicationContext()),
                ShufflePrefs.discoveryRatio(app.getApplicationContext()),
                new HashSet<>(sessionSkips),
                library.likedIds(),
                seed.id);

        // Verify generation hasn't changed (user may have toggled away rapidly).
        if (generation != smartGeneration.get()) {
            return;
        }

        // Rebuild the queue: seed first, then curated order.
        List<MediaItem> rebuilt = new ArrayList<>();
        rebuilt.add(toMediaItem(seed));
        for (Track t : curated) {
            rebuilt.add(toMediaItem(t));
        }
        controller.setMediaItems(rebuilt, 0, position);
        if (playing) {
            controller.play();
        }
        Log.i(TAG, "smart queue curated " + allTracks.size() + " tracks into " + curated.size());
    }

    // ---- Radio ----

    private Track currentTrack() {
        if (controller == null) {
            return null;
        }
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null) {
            return null;
        }
        if (current.localConfiguration != null && current.localConfiguration.tag instanceof Track) {
            return (Track) current.localConfiguration.tag;
        }
        return trackFromMetadata(current.mediaMetadata, current.mediaId);
    }

    /**
     * When the queue runs out (repeat off) the player keeps going with a fresh
     * batch of related songs seeded by the track that just finished.
     * Only radios in Smart mode; Off mode stops at queue end; Normal uses
     * Media3's own shuffle/loop.
     */
    private void radioContinue() {
        if (controller == null || radioBusy) {
            return;
        }
        // RANDOM owns the current queue. Never fall through to radio/
        // recommendations when the queue reaches its end.
        if (ShufflePrefs.NORMAL.equals(ShufflePrefs.mode(app.getApplicationContext()))) {
            return;
        }
        // Off mode: no radio, no auto-continue.
        if (ShufflePrefs.isOff(app.getApplicationContext())) {
            return;
        }
        if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            return; // repeat handles looping on its own
        }
        Track seed = currentTrack();
        if (seed == null) {
            return;
        }
        int gen = smartGeneration.get();
        // Give the UI a moment to surface the ended state, then continue.
        main.postDelayed(() -> fetchRadio(seed, 30, false, gen), 400);
    }

    /**
     * Proactively tops up the queue when remaining upcoming tracks are running
     * low. This prevents the player from stuttering or showing an empty queue
     * while waiting for the next radio batch.
     */
    private void checkProactiveTopUp() {
        if (controller == null || radioBusy) {
            return;
        }
        if (!ShufflePrefs.isSmart(app.getApplicationContext())) {
            return;
        }
        if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            return;
        }
        int remaining = controller.getMediaItemCount() - controller.getCurrentMediaItemIndex() - 1;
        if (remaining > TOPUP_THRESHOLD) {
            return;
        }
        Track seed = currentTrack();
        if (seed == null) {
            return;
        }
        int gen = smartGeneration.get();
        Log.i(TAG, "smart proactive top-up: " + remaining + " tracks remaining");
        fetchRadio(seed, 24, true, gen);
    }

    /**
     * Fetches a related-song batch. append=true adds it behind the current
     * queue (shuffle mixing); append=false replaces the exhausted queue and
     * starts playing (auto-continue). Transient failures (e.g. a cold-start
     * timeout against the server) are retried once after a short delay so a
     * flaky first request doesn't leave shuffle/auto-next dead.
     *
     * Every request carries a generation token. If the user toggles modes or
     * changes tracks while the request is in-flight, the generation will have
     * changed and the stale response is silently discarded.
     */
    private void fetchRadio(Track seed, int limit, boolean append, int generation) {
        fetchRadio(seed, limit, append, generation, 0, false);
    }

    private void fetchRadio(Track seed, int limit, boolean append, int generation, int attempt) {
        fetchRadio(seed, limit, append, generation, attempt, false);
    }

    /**
     * @param insertAfterCurrent when true the batch lands directly behind
     *     the playing track (behind any manual "Play Next" items) in the
     *     provider's relevance order — the session-initial radio, where
     *     the top suggestion is exactly what should play next.
     */
    private void fetchRadio(Track seed, int limit, boolean append, int generation, int attempt, boolean insertAfterCurrent) {
        if (radioBusy) {
            return;
        }
        NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
        radioBusy = true;
        lastRadioSeedId = seed.id;
        // append=true requests may be served from the short-lived cache;
        // auto-continue (append=false) must always hit the network.
        nightLight.getMusicRepository().fetchRadio(seed, limit, append, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                // Race protection: discard unless BOTH the generation token and
                // the seed are still current. A stale response for song A must
                // never mutate the queue now built around song B.
                String requestSeed = lastRadioSeedId;
                if (generation != smartGeneration.get()
                        || requestSeed == null || !requestSeed.equals(seed.id)) {
                    radioBusy = false;
                    Log.i(TAG, "radio stale (gen " + generation + " != " + smartGeneration.get()
                            + " or seed " + requestSeed + " != " + seed.id + "), discarding");
                    return;
                }
                radioBusy = false;
                if (tracks.isEmpty() || controller == null) {
                    return;
                }
                List<Track> curated = tracks;
                if (insertAfterCurrent) {
                    // The provider's suggestion order IS the relevance
                    // order (top suggestion = most related track), so
                    // the session-initial batch keeps it — only the seed
                    // itself, skipped artists and duplicates are stripped.
                    // Re-curating here would re-roll the order and could
                    // bury the track the user is most likely to want next.
                    curated = filterRadioBatch(seed, tracks);
                } else if (ShufflePrefs.isSmart(app.getApplicationContext())) {
                    // Smart Shuffle: re-order + re-weight the batch around the
                    // current context (mood, recent plays, skips, likes) using
                    // weighted random selection with artist/album diversity.
                    SmartShuffleEngine engine = new SmartShuffleEngine();
                    LibraryRepository library = nightLight.getLibraryRepository();
                    curated = engine.generateQueue(
                            seed, tracks, new ArrayList<>(sessionRecent),
                            com.nightlight.app.util.AccountPrefs.effectiveMood(app.getApplicationContext()),
                            ShufflePrefs.discoveryRatio(app.getApplicationContext()),
                            new HashSet<>(sessionSkips),
                            library.likedIds(),
                            controller.getCurrentMediaItem() != null
                                    ? (controller.getCurrentMediaItem().localConfiguration != null
                                    && controller.getCurrentMediaItem().localConfiguration.tag instanceof Track)
                                    ? ((Track) controller.getCurrentMediaItem().localConfiguration.tag).id
                                    : controller.getCurrentMediaItem().mediaId
                                    : null);
                    Log.i(TAG, "smart shuffle curated " + tracks.size() + " -> " + curated.size());
                }
                /**
         * A batch that answers a pending Autoplay hand-over becomes the new
         * AUTOPLAY context rather than being appended to the queue that just
         * ran out — that is the whole point of Autoplay being a separate layer.
         */
        if (!append && autoplayPendingSeedId != null
                && autoplayPendingSeedId.equals(requestSeed)) {
            autoplayPendingSeedId = null;
            installAutoplayContext(curated, seed);
            return;
        }
                if (append) {
                    if (insertAfterCurrent) {
                        insertAfterCurrent(curated);
                        Log.i(TAG, "radio first: seed=" + seed.id + " candidates=" + tracks.size()
                                + " curated=" + curated.size() + " queueSize=" + controller.getMediaItemCount()
                                + " next=" + nextArtists(curated, 3));
                    } else {
                        addToQueue(curated);
                        Log.i(TAG, "smart queue: seed=" + seed.id + " candidates=" + tracks.size()
                                + " curated=" + curated.size() + " queueSize=" + controller.getMediaItemCount()
                                + " next=" + nextArtists(curated, 3));
                    }
                } else {
                    Log.i(TAG, "radio continue: seed=" + seed.id + " curated=" + curated.size()
                            + " next=" + nextArtists(curated, 3));
                    if (controller.getPlaybackState() == Player.STATE_ENDED) {
                        playTracks(curated, 0);
                    }
                }
            }

            @Override
            public void onFailure(Throwable error) {
                radioBusy = false;
                if (attempt == 0) {
                    Log.w(TAG, "radio fetch failed (" + error + "); retrying once");
                    main.postDelayed(() -> fetchRadio(seed, limit, append, generation, 1, insertAfterCurrent), 3000);
                } else {
                    Log.w(TAG, "radio fetch failed after retry: " + error);
                }
            }
        });
    }

    /**
     * Concise, secret-free curation record: the first artists the queue will
     * actually play. Never logs URLs, tokens, or credentials.
     */
    private static String nextArtists(List<Track> tracks, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tracks.size() && i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String a = tracks.get(i).artists;
            sb.append(a != null && a.length() > 24 ? a.substring(0, 24) : a);
        }
        return sb.append(']').toString();
    }

    public void clearQueue() {
        if (controller == null) {
            return;
        }
        controller.clearMediaItems();
        // The session order references tracks, not queue indexes, but an
        // emptied queue means nothing is playing — drop the session so a later
        // play() cannot inherit an order with no anchor.
        activeContext = null;
        activeSession = null;
        autoplayPendingSeedId = null;
        queueManager.manualQueue().clear();
        publish(true);
    }

    /** Current queue as tracks, in playback order. */
    public List<Track> getQueueTracks() {
        List<Track> tracks = new ArrayList<>();
        if (controller == null) {
            return tracks;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            Track t = item.localConfiguration != null ? (Track) item.localConfiguration.tag : null;
            if (t == null) {
                t = trackFromMetadata(item.mediaMetadata, item.mediaId);
            }
            if (t != null) {
                tracks.add(t);
            }
        }
        return tracks;
    }

    public void removeQueueItem(int index) {
        if (controller == null) {
            return;
        }
        controller.removeMediaItem(index);
        // Removing from the player queue does not change the session order; the
        // removed track simply never gets played from this session.
        publish(true);
    }

    // ---- Internals ----

    /**
     * Collects all track IDs currently in the Media3 queue. Used for
     * deduplication before inserting new tracks.
     */
    private Set<String> existingQueueIds() {
        Set<String> ids = new HashSet<>();
        if (controller == null) {
            return ids;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            ids.add(item.mediaId);
        }
        return ids;
    }

    private MediaItem toMediaItem(Track track) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(track.name)
                .setArtist(track.artists)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.imageUrl == null || track.imageUrl.isEmpty()
                        ? null : Uri.parse(track.imageUrl))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build();

        return new MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.streamUrl)
                .setTag(track)
                .setMediaMetadata(metadata)
                .build();
    }

    /**
     * Single choke point for "a track became current". Called from the
     * player transition callback and from track-start tracking; the shared
     * {@link #lastTransitionId} makes the second call for the same track a
     * no-op, so the generation moves exactly once per track change.
     *
     * <p>The session-initial radio request is seeded on the anchor itself,
     * so the player's own start onto that anchor must NOT invalidate it:
     * the first landing on {@link #pendingRadioAnchorId} clears the
     * exemption without a bump. Any other move (manual next, a skip past
     * the anchor, a different session) bumps as before.
     */
    private void noteTrackTransition(String newId) {
        if (newId == null || newId.equals(lastTransitionId)) {
            return;
        }
        lastTransitionId = newId;
        if (pendingRadioAnchorId != null) {
            if (pendingRadioAnchorId.equals(newId)) {
                pendingRadioAnchorId = null;
                return;
            }
            pendingRadioAnchorId = null;
        }
        smartGeneration.incrementAndGet();
    }

    private void handleTrackStart() {
        if (controller == null || !controller.isPlaying()) {
            return;
        }
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null) {
            return;
        }
        Track track = current.localConfiguration != null ? (Track) current.localConfiguration.tag : null;
        if (track == null) {
            track = trackFromMetadata(current.mediaMetadata, current.mediaId);
        }
        if (track != null && !track.id.equals(recordedTrackId)) {
            recordedTrackId = track.id;
            syncSessionCursorTo(track.id);
            playReportedTrackId = null;
            currentTrackStartedAt = System.currentTimeMillis();
            // The seed changed: bump the generation so any in-flight radio
            // response for the previous track is discarded instead of mutating
            // the queue built around the new one. Landing on the session's
            // own anchor is exempt (see noteTrackTransition).
            noteTrackTransition(track.id);
            sessionRecent.add(track);
            if (sessionRecent.size() > MAX_SESSION_RECENT) {
                sessionRecent.remove(0);
            }
            if (trackStartedListener != null) {
                trackStartedListener.onTrackStarted(track);
            }
        }
    }

    /** This session's recently played tracks (for Smart Shuffle context). */
    public List<Track> getSessionRecent() {
        return new ArrayList<>(sessionRecent);
    }

    /**
     * Total listened time for the signed-in account: the persisted taste
     * profile's total plus whatever has accrued in this process. Falls back to
     * the in-session figure alone when no profile scope exists yet (guest or
     * first run), so callers always get a usable number.
     */
    public long getTotalPlaytimeMs() {
        long persisted = 0L;
        try {
            com.nightlight.app.reco.TasteProfile taste = new com.nightlight.app.reco.TasteProfile();
            com.nightlight.app.util.TastePrefs.loadInto(app,
                    com.nightlight.app.util.TastePrefs.scopeKey(app), taste);
            persisted = taste.totalPlaytimeMs();
        } catch (Exception ignored) {
            // No stored profile (or prefs unavailable) — session total only.
        }
        return persisted + sessionPlaytimeMs;
    }

    /**
     * Records an explicit graded feedback signal (like / unlike) into the taste
     * profile that drives recommendation ranking. Persisted immediately
     * because these taps are rare and high-value.
     *
     * <p>Never throws: feedback bookkeeping must not be able to break the
     * playback caller's own work.
     */
    public void recordSignal(Track track, com.nightlight.app.reco.Signal signal) {
        if (track == null || signal == null) {
            return;
        }
        try {
            String scope = com.nightlight.app.util.TastePrefs.scopeKey(app);
            com.nightlight.app.reco.TasteProfile taste = new com.nightlight.app.reco.TasteProfile();
            com.nightlight.app.util.TastePrefs.loadInto(app, scope, taste);
            taste.update(track, signal);
            com.nightlight.app.util.TastePrefs.save(app, scope, taste);
        } catch (Exception ignored) {
            // Feedback is best-effort; playback and the like toggle stand.
        }
    }

    /**
     * Reports a threshold-crossed play once per track load. Screen opens and
     * bare playback starts never count — only sustained listening does.
     * Fire-and-forget; safe to call from the 500ms ticker.
     */
    private void maybeReportPlayThreshold() {
        PlaybackSnapshot snap = snapshot;
        if (snap == null || !snap.isPlaying || snap.current == null || snap.current.id == null) {
            return;
        }
        if (snap.current.id.equals(playReportedTrackId)) {
            return;
        }
        long duration = snap.duration > 0 ? snap.duration : snap.current.durationMs;
        if (duration <= 0) {
            return;
        }
        long threshold = Math.min(PLAY_THRESHOLD_MS, duration / 2);
        if (snap.position < threshold) {
            return;
        }
        playReportedTrackId = snap.current.id;
        List<String> artists = new ArrayList<>();
        if (snap.current.artists != null) {
            for (String part : snap.current.artists.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    artists.add(name);
                }
            }
        }
        try {
            NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
            nightLight.getLibraryRepository().reportPlayPosition(
                    snap.current.id, snap.current.name, artists, snap.position, duration);
        } catch (Exception ignored) {
            // Popularity reporting must never disturb playback.
        }
    }

    private Track trackFromMetadata(MediaMetadata metadata, String id) {
        if (metadata == null || id == null) {
            return null;
        }
        return new Track(id,
                metadata.title != null ? metadata.title.toString() : "Unknown",
                metadata.artist != null ? metadata.artist.toString() : "",
                metadata.albumTitle != null ? metadata.albumTitle.toString() : "",
                metadata.artworkUri != null ? metadata.artworkUri.toString() : "",
                null, 0L, "");
    }

    private void publish(boolean force) {
        if (controller == null) {
            return;
        }
        MediaItem current = controller.getCurrentMediaItem();
        Track track = null;
        if (current != null) {
            track = current.localConfiguration != null ? (Track) current.localConfiguration.tag : null;
            if (track == null) {
                track = trackFromMetadata(current.mediaMetadata, current.mediaId);
            }
        }

        int state = controller.getPlaybackState();
        boolean buffering = state == Player.STATE_BUFFERING;
        boolean ended = state == Player.STATE_ENDED;
        long position = ended ? 0 : controller.getCurrentPosition();
        if (position == C.TIME_UNSET) position = 0;
        long ctrlDuration = controller.getDuration();
        long duration = (ctrlDuration != C.TIME_UNSET && ctrlDuration > 0) ? ctrlDuration
                : (track != null ? track.durationMs : 0L);

        String error = null;
        if (controller.getPlayerError() != null) {
            PlaybackException e = controller.getPlayerError();
            error = e.getErrorCodeName() != null ? e.getErrorCodeName() : e.getMessage();
        }

        int currentIndex = controller.getCurrentMediaItemIndex();
        PlaybackSnapshot next = new PlaybackSnapshot(
                track,
                controller.isPlaying(),
                buffering,
                position,
                duration,
                controller.getMediaItemCount() > 0,
                controller.getRepeatMode(),
                controller.getShuffleModeEnabled(),
                error,
                controller.getMediaItemCount(),
                currentIndex,
                true,
                null);

        boolean changed = force
                || !next.equalsContent(snapshot)
                || Math.abs(next.position - snapshot.position) > 300
                || next.duration != snapshot.duration;
        snapshot = next;

        if (changed && !listeners.isEmpty()) {
            for (Listener l : listeners) {
                l.onPlaybackChanged(next);
            }
        }

        if (next.isPlaying && !tickerRunning) {
            tickerRunning = true;
            main.removeCallbacks(ticker);
            main.postDelayed(ticker, TICKER_MS);
        } else if (!next.isPlaying && tickerRunning) {
            tickerRunning = false;
            main.removeCallbacks(ticker);
        }
    }
}

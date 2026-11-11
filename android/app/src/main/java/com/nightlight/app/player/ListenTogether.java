package com.nightlight.app.player;

import android.content.Context;
import android.util.Log;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.SessionsDtos;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.ui.common.TrackPlayer;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * "Listen together": keeps two or more devices on the same song at the same
 * time. The host shares a 6-letter code; joiners poll the session and align
 * their playback (track switch, seek, play/pause). The host publishes its
 * playback state on the same cadence.
 */
public final class ListenTogether {

    private static final String TAG = "ListenTogether";
    private static final long POLL_MS = 3000;
    private static final long DRIFT_TOLERANCE_MS = 2500;
    private static final long CHAT_BASE_MS = 4000;
    private static final long CHAT_MAX_MS = 8000;
    private static final long STATE_HEARTBEAT_MS = 15000;

    private static final ListenTogether INSTANCE = new ListenTogether();

    public interface CodeCallback {
        void onCode(String code);

        void onError(String message);
    }

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "listen-together");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<String> activeCode = new AtomicReference<>(null);
    private final AtomicBoolean hosting = new AtomicBoolean(false);
    private final AtomicLong lastPublished = new AtomicLong(0);
    private volatile ScheduledFuture<?> hostPublishFuture;
    private volatile ScheduledFuture<?> followerPollFuture;

    // Chat state
    private final AtomicLong lastChatPoll = new AtomicLong(0);
    private final List<SessionsDtos.ChatMessage> chatMessages =
            Collections.synchronizedList(new ArrayList<>());
    /** O(1) dedupe — rebuilt only when the list is cleared. */
    private final java.util.Set<String> knownChatIds =
            Collections.synchronizedSet(new java.util.HashSet<>());
    private static final int MAX_CHAT_LOCAL = 100;
    private volatile ChatListener chatListener;
    private ScheduledExecutorService chatExecutor;
    private volatile ScheduledFuture<?> chatFuture;
    private int chatEmptyStreak = 0;
    private long nextChatDelay = CHAT_BASE_MS;

    // State debounce
    private volatile String lastPublishedTrackId;
    private volatile boolean lastPublishedPlaying;
    private volatile long lastPublishedPosition;
    private volatile long lastStateHeartbeat;

    public interface ChatListener {
        void onMessages(List<SessionsDtos.ChatMessage> messages);
    }

    public void setChatListener(ChatListener listener) {
        this.chatListener = listener;
        if (listener != null) {
            // Ensure polling while chat UI is visible
            if (isActive() && (chatExecutor == null || chatExecutor.isShutdown())) {
                startChatPollingInternal();
            }
        } else {
            // Pause chat polling when UI leaves to save battery/bandwidth
            // Keep messages cached; polling resumes when listener re-attaches
            stopChatPollingInternal();
        }
    }

    public List<SessionsDtos.ChatMessage> getChatMessages() {
        return new ArrayList<>(chatMessages);
    }

    public void sendChatMessage(Context context, String message, final com.nightlight.app.data.api.NightLightApi apiCallback) {
        final String code = activeCode.get();
        if (code == null) return;
        final String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty() || trimmed.length() > 280) return;
        final Context app = context.getApplicationContext();
        final String myName = com.nightlight.app.util.AccountPrefs.ensureUsername(app);
        final String deviceId = TokenStore.getDeviceId();
        executor.execute(() -> {
            SessionsDtos.ChatSendRequest req = new SessionsDtos.ChatSendRequest(
                    deviceId, myName, trimmed);
            try {
                ApiResponse<SessionsDtos.ChatMessage> body =
                        ApiClient.nightLightApi(app).sendChatMessage(code, req).execute().body();
                // Optimistic local add (server echo arrives via poll; dedupe by id).
                SessionsDtos.ChatMessage local = new SessionsDtos.ChatMessage();
                local.id = body != null && body.data != null && body.data.id != null
                        ? body.data.id : ("local-" + System.currentTimeMillis() + "-" + deviceId.hashCode());
                local.deviceId = deviceId;
                local.name = body != null && body.data != null && body.data.name != null
                        ? body.data.name : myName;
                local.text = trimmed;
                local.createdAt = body != null && body.data != null && body.data.createdAt > 0
                        ? body.data.createdAt : System.currentTimeMillis();
                if (knownChatIds.add(local.id)) {
                    chatMessages.add(local);
                    pruneChat();
                    if (chatListener != null) {
                        List<SessionsDtos.ChatMessage> snapshot = new ArrayList<>(chatMessages);
                        AppExecutors.onMain(() -> {
                            ChatListener l = chatListener;
                            if (l != null) l.onMessages(snapshot);
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "chat send failed", e);
            }
        });
    }

    public void startChatPolling() {
        startChatPollingInternal();
    }

    private void startChatPollingInternal() {
        if (chatExecutor != null && !chatExecutor.isShutdown()) {
            // already running, ensure future scheduled
            if (chatFuture == null || chatFuture.isCancelled()) {
                scheduleNextChat(CHAT_BASE_MS);
            }
            return;
        }
        chatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lt-chat");
            t.setDaemon(true);
            return t;
        });
        chatEmptyStreak = 0;
        nextChatDelay = CHAT_BASE_MS;
        scheduleNextChat(800);
    }

    private void scheduleNextChat(long delayMs) {
        if (chatExecutor == null || chatExecutor.isShutdown()) return;
        if (chatFuture != null) chatFuture.cancel(false);
        chatFuture = chatExecutor.schedule(() -> {
            pollChat();
            // Backoff when idle, reset when fresh messages arrive
            long next = chatEmptyStreak == 0 ? CHAT_BASE_MS : Math.min(CHAT_BASE_MS * (1L << Math.min(chatEmptyStreak, 3)), CHAT_MAX_MS);
            scheduleNextChat(next);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void stopChatPollingInternal() {
        if (chatFuture != null) {
            chatFuture.cancel(false);
            chatFuture = null;
        }
        // Don't shutdown executor immediately - keep for resume; shutdown on stop()
    }

    public void stopChatPolling() {
        if (chatFuture != null) {
            chatFuture.cancel(false);
            chatFuture = null;
        }
        if (chatExecutor != null) {
            chatExecutor.shutdownNow();
            chatExecutor = null;
        }
        chatEmptyStreak = 0;
    }

    private void pollChat() {
        final String code = activeCode.get();
        if (code == null || appContext == null) return;
        // Only poll while a UI cares (lifecycle-aware per spec)
        if (chatListener == null) {
            chatEmptyStreak++;
            return;
        }
        try {
            long after = lastChatPoll.get();
            ApiResponse<SessionsDtos.ChatMessagesResponse> body =
                    ApiClient.nightLightApi(appContext).getChatMessages(code, after).execute().body();
            if (body != null && body.success && body.data != null && !body.data.isEmpty()) {
                // Dedupe by id via the persistent known-set (O(1)) and only
                // advance the cursor to the newest received createdAt.
                long newest = after;
                List<SessionsDtos.ChatMessage> fresh = new ArrayList<>();
                for (SessionsDtos.ChatMessage m : body.data) {
                    if (m == null || m.id == null) continue;
                    if (knownChatIds.add(m.id)) {
                        fresh.add(m);
                        newest = Math.max(newest, m.createdAt);
                    } else if (m.createdAt > newest) {
                        newest = Math.max(newest, m.createdAt);
                    }
                }
                if (!fresh.isEmpty()) {
                    chatMessages.addAll(fresh);
                    pruneChat();
                    lastChatPoll.set(newest);
                    chatEmptyStreak = 0;
                    if (chatListener != null) {
                        List<SessionsDtos.ChatMessage> snapshot = new ArrayList<>(chatMessages);
                        AppExecutors.onMain(() -> {
                            ChatListener l = chatListener;
                            if (l != null) l.onMessages(snapshot);
                        });
                    }
                } else {
                    chatEmptyStreak++;
                }
            } else {
                chatEmptyStreak++;
            }
            // On empty: keep the cursor — advancing to now() would skip messages
            // with clock skew. Backoff via scheduleNextChat handles idle polling.
        } catch (Exception e) {
            // Transient network gap — retry next tick with backoff.
            chatEmptyStreak++;
        }
    }

    /** Keeps the local chat buffer bounded so rendering stays O(100), not O(n). */
    private void pruneChat() {
        while (chatMessages.size() > MAX_CHAT_LOCAL) {
            SessionsDtos.ChatMessage removed = chatMessages.remove(0);
            if (removed != null && removed.id != null) {
                // Keep the id reserved so a late poll echo can't re-insert it.
            }
        }
    }

    private Context appContext;
    private PlaybackManager.Listener hostPlaybackListener;

    private ListenTogether() {
    }

    public static ListenTogether get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return activeCode.get() != null;
    }

    public String activeCode() {
        return activeCode.get();
    }

    // ---- Hosting ----

    public void startHosting(Context context, CodeCallback callback) {
        stopIfActive();
        appContext = context.getApplicationContext();
        final PlaybackSnapshot snap = PlaybackManager.get(context).getSnapshot();
        final Track track = snap != null ? snap.current : null;
        if (track == null) {
            callback.onError("Nothing is playing to share");
            return;
        }
        final String myName = com.nightlight.app.util.AccountPrefs.ensureUsername(appContext);
        executor.execute(() -> {
            SessionsDtos.CreateRequest req = new SessionsDtos.CreateRequest();
            req.deviceId = TokenStore.getDeviceId();
            req.name = myName;
            req.track = toSnapshot(track);
            try {
                ApiResponse<SessionsDtos.SessionDto> body =
                        ApiClient.nightLightApi(appContext).createSession(req).execute().body();
                if (body == null || !body.success || body.data == null) {
                    AppExecutors.onMain(() -> callback.onError("Couldn't start the session"));
                    return;
                }
                final String code = body.data.code;
                activeCode.set(code);
                hosting.set(true);
                lastPublished.set(0);
                scheduleHostPublish();
                Log.w(TAG, "hosting session code=" + code);
                startChatPolling();
                AppExecutors.onMain(() -> callback.onCode(code));
            } catch (Exception e) {
                Log.w(TAG, "create failed", e);
                AppExecutors.onMain(() -> callback.onError("Couldn't start the session"));
            }
        });
    }

    /** Launches the system share sheet with the join link. */
    public static void shareCode(Context context, String code) {
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT,
                context.getString(com.nightlight.app.R.string.listen_share_title));
        String link = "https://nightlight-api.onrender.com/l/" + code;
        String body = "Listen with me on NightLight — session " + code + "\n\n" + link
                + "\n\nIf the app does not open automatically, enter code " + code + " in NightLight.";
        send.putExtra(android.content.Intent.EXTRA_TEXT, body);
        context.startActivity(android.content.Intent.createChooser(send,
                context.getString(com.nightlight.app.R.string.listen_share_title)));
    }

    public void stop() {
        hosting.set(false);
        activeCode.set(null);
        chatMessages.clear();
        knownChatIds.clear();
        lastChatPoll.set(0);
        chatEmptyStreak = 0;
        stopChatPolling();
        ScheduledFuture<?> host = hostPublishFuture;
        if (host != null) {
            host.cancel(false);
            hostPublishFuture = null;
        }
        ScheduledFuture<?> follower = followerPollFuture;
        if (follower != null) {
            follower.cancel(false);
            followerPollFuture = null;
        }
        if (hostPlaybackListener != null && appContext != null) {
            try { PlaybackManager.get(appContext).removeListener(hostPlaybackListener); } catch (Exception ignored) {}
            hostPlaybackListener = null;
        }
        lastPublishedTrackId = null;
        lastStateHeartbeat = 0;
    }

    private void stopIfActive() {
        if (activeCode.get() != null) {
            stop();
        }
    }

    private void scheduleHostPublish() {
        ScheduledFuture<?> old = hostPublishFuture;
        if (old != null) old.cancel(false);
        lastStateHeartbeat = 0;
        lastPublishedTrackId = null;
        if (hostPlaybackListener == null && appContext != null) {
            hostPlaybackListener = snapshot -> {
                // Debounced trigger on any playback change while hosting
                if (hosting.get()) notifyStateChanged();
            };
            PlaybackManager.get(appContext).addListener(hostPlaybackListener);
        }
        hostPublishFuture = executor.scheduleAtFixedRate(() -> publishState(false), 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Called when track/playing/seek changes — debounced immediate publish. */
    public void notifyStateChanged() {
        if (!hosting.get() || activeCode.get() == null) return;
        executor.execute(() -> publishState(true));
    }

    private void publishState() {
        publishState(false);
    }

    private void publishState(boolean isTrigger) {
        final String code = activeCode.get();
        if (code == null || !hosting.get() || appContext == null) {
            return;
        }
        final PlaybackSnapshot snap = PlaybackManager.get(appContext).getSnapshot();
        if (snap == null || snap.current == null) {
            return;
        }
        String trackId = snap.current.id;
        boolean playing = snap.isPlaying;
        long pos = snap.position;
        long now = System.currentTimeMillis();

        boolean trackChanged = !trackId.equals(lastPublishedTrackId);
        boolean playingChanged = playing != lastPublishedPlaying;
        boolean seeked = Math.abs(pos - lastPublishedPosition) > 2000;
        boolean heartbeatDue = now - lastStateHeartbeat > STATE_HEARTBEAT_MS;

        boolean shouldPublish = trackChanged || playingChanged || seeked || heartbeatDue || isTrigger;

        // For periodic ticks without trigger, only heartbeat or meaningful change should publish
        if (!shouldPublish && !isTrigger) {
            // Coalesce: still check heartbeat
            if (!heartbeatDue) return;
            shouldPublish = true;
        }
        // Debounce: if nothing meaningful and not heartbeat, skip
        if (!trackChanged && !playingChanged && !seeked && !heartbeatDue && !isTrigger) {
            return;
        }

        SessionsDtos.UpdateStateRequest req = new SessionsDtos.UpdateStateRequest();
        req.deviceId = TokenStore.getDeviceId();
        req.track = toSnapshot(snap.current);
        req.positionMs = pos;
        req.playing = playing;
        try {
            ApiClient.nightLightApi(appContext).updateSessionState(code, req).execute();
            lastPublishedTrackId = trackId;
            lastPublishedPlaying = playing;
            lastPublishedPosition = pos;
            lastStateHeartbeat = now;
            lastPublished.set(now);
        } catch (Exception e) {
            // Next tick/trigger retries; sessions tolerate gaps.
        }
    }

    // ---- Joining ----

    public void join(Context context, String code, CodeCallback callback) {
        appContext = context.getApplicationContext();
        final String myName = com.nightlight.app.util.AccountPrefs.ensureUsername(appContext);
        executor.execute(() -> {
            SessionsDtos.JoinRequest req = new SessionsDtos.JoinRequest();
            req.code = code.trim().toUpperCase();
            req.deviceId = TokenStore.getDeviceId();
            req.name = myName;
            try {
                ApiResponse<SessionsDtos.SessionDto> body =
                        ApiClient.nightLightApi(appContext).joinSession(req).execute().body();
                if (body == null || !body.success || body.data == null || body.data.state == null) {
                    AppExecutors.onMain(() -> callback.onError("Session not found — check the code"));
                    return;
                }
                final SessionsDtos.SessionDto session = body.data;
                stopIfActive();
                activeCode.set(session.code);
                hosting.set(false);
                scheduleFollowerPoll();
                Log.w(TAG, "joined session code=" + session.code);
                startChatPolling();
                // Playback calls must run on the MediaController's thread (main).
                AppExecutors.onMain(() -> {
                    playRemote(session.state);
                    callback.onCode(session.code);
                });
            } catch (Exception e) {
                Log.w(TAG, "join failed", e);
                AppExecutors.onMain(() -> callback.onError("Couldn't join — check your connection"));
            }
        });
    }

    private void scheduleFollowerPoll() {
        ScheduledFuture<?> old = followerPollFuture;
        if (old != null) old.cancel(false);
        followerPollFuture = executor.scheduleAtFixedRate(this::follow, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void follow() {
        final String code = activeCode.get();
        if (code == null || hosting.get() || appContext == null) {
            return;
        }
        try {
            ApiResponse<SessionsDtos.SessionDto> body =
                    ApiClient.nightLightApi(appContext).getSession(code).execute().body();
            if (body == null || !body.success || body.data == null) {
                return;
            }
            final SessionsDtos.SessionState remote = body.data.state;
            AppExecutors.onMain(() -> playRemote(remote));
        } catch (Exception e) {
            // Transient network gap — retry next tick.
        }
    }

    /** Aligns local playback with the remote session state (main thread). */
    private void playRemote(SessionsDtos.SessionState remote) {
        if (remote == null || remote.track == null || appContext == null) {
            return;
        }
        PlaybackSnapshot local = PlaybackManager.get(appContext).getSnapshot();
        Track localTrack = local != null ? local.current : null;
        boolean sameTrack = localTrack != null && localTrack.id.equals(remote.track.id);

        if (!sameTrack) {
            UserDtos.TrackSnapshotDto dto = new UserDtos.TrackSnapshotDto();
            dto.id = remote.track.id;
            dto.name = remote.track.name;
            dto.artists = remote.track.artists;
            dto.album = remote.track.album;
            dto.imageUrl = remote.track.imageUrl;
            dto.duration = (int) remote.track.duration;
            dto.year = remote.track.year;
            Track snapshot = Track.fromSnapshot(dto);
            TrackPlayer.play(appContext, Collections.singletonList(snapshot), 0);
            if (!remote.playing) {
                final Context pauseContext = appContext;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> PlaybackManager.get(pauseContext).pause(), 350);
            }
            // First position correction comes on the next follower tick.
            return;
        }

        if (local == null) {
            return;
        }
        long remoteAge = Math.max(0, System.currentTimeMillis() - remote.updatedAt);
        long expected = remote.positionMs + (remote.playing ? remoteAge : 0);

        Log.d(TAG, "follow remote=" + remote.playing + " exp=" + expected + " local=" + local.isPlaying
                + " pos=" + local.position + " drift=" + (local.position - expected));
        if (remote.playing && !local.isPlaying) {
            if (local.isBuffering || (local.position < 200 && expected < 1500)) {
                // Already requested play — wait for buffering to finish.
                return;
            }
            Log.d(TAG, "follow action=resume");
            PlaybackManager.get(appContext).togglePlayPause();
            return;
        }
        if (!remote.playing && local.isPlaying) {
            Log.d(TAG, "follow action=pause");
            PlaybackManager.get(appContext).pause();
            return;
        }
        if (!remote.playing && Math.abs(local.position - expected) > DRIFT_TOLERANCE_MS) {
            Log.d(TAG, "follow action=seek paused to " + expected);
            PlaybackManager.get(appContext).seekTo(Math.max(0, expected));
            return;
        }
        if (remote.playing && Math.abs(local.position - expected) > DRIFT_TOLERANCE_MS) {
            Log.d(TAG, "follow action=seek to " + Math.max(0, expected - 400));
            PlaybackManager.get(appContext).seekTo(Math.max(0, expected - 400));
        }
    }

    private static SessionsDtos.TrackSnapshot toSnapshot(Track track) {
        SessionsDtos.TrackSnapshot s = new SessionsDtos.TrackSnapshot();
        s.id = track.id;
        s.name = track.name;
        s.artists = track.artists;
        s.album = track.album;
        s.imageUrl = track.imageUrl;
        s.duration = track.durationMs;
        s.year = track.year;
        return s;
    }
}

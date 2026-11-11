package com.nightlight.app.player;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.nightlight.app.R;
import com.nightlight.app.ui.NowPlayingActivity;

import java.io.File;

/**
 * Foreground media service. Hosts the ExoPlayer instance and the MediaSession;
 * playback survives screen changes, backgrounding and lock (Media3's default
 * notification provider renders system media controls). Audio focus is handled
 * by ExoPlayer (pause on transient loss, duck on transient-can-duck, stop on
 * permanent loss, resume on regain).
 */
@UnstableApi
public final class PlaybackService extends MediaSessionService {

    private ExoPlayer player;
    private MediaSession mediaSession;
    private SimpleCache cache;

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        // Stream cache: 200 MB LRU so scrubbing and repeat plays avoid refetching.
        try {
            File cacheDir = new File(getCacheDir(), "streams");
            cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024));
        } catch (Exception e) {
            cache = null;
        }

        DataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("NightLight/1.0 (Android)")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000);
        DataSource.Factory dataSourceFactory = cache != null
                ? new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(httpFactory)
                : httpFactory;

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory))
                .setHandleAudioBecomingNoisy(true)
                .build();

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(PendingIntentFactory.nowPlaying(this))
                .build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Standard music-app behavior: keep playing when the task is removed.
        if (player != null && !player.getPlayWhenReady()) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (cache != null) {
            cache.release();
            cache = null;
        }
        super.onDestroy();
    }

    static final class PendingIntentFactory {
        static android.app.PendingIntent nowPlaying(android.content.Context context) {
            Intent intent = new Intent(context, NowPlayingActivity.class);
            return android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        }
    }
}
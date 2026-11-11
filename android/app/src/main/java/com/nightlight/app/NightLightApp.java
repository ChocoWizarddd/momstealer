package com.nightlight.app;

import android.app.Application;

import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

public final class NightLightApp extends Application {

    private static NightLightApp instance;

    private LibraryRepository libraryRepository;
    private PlaylistRepository playlistRepository;
    private MusicRepository musicRepository;
    private AuthRepository authRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Crash capture first: any failure after this point (init,
        // search, playback) lands on a report screen with the full
        // stack trace, so manually installed builds can report back
        // without adb.
        com.nightlight.app.util.CrashReporter.install(this);

        // Single shuffle: Smart is the only mode. Upgrade any legacy
        // persisted choice (Normal/Off from earlier builds) so every
        // playback path runs the same radio-first behavior.
        com.nightlight.app.util.ShufflePrefs.setMode(
                this, com.nightlight.app.util.ShufflePrefs.SMART);

        TokenStore.init(this);
        com.nightlight.app.data.api.FirebaseAuthClient.apiKey = BuildConfig.FIREBASE_API_KEY;
        com.nightlight.app.data.api.GoogleSignInHelper.serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID;
        // Fail-fast diagnostics: never log key values, only empty/non-empty.
        // Release builds without -PnightlightFirebaseKey / -PnightlightGoogleClientId
        // (or FIREBASE_WEB_API_KEY / GOOGLE_WEB_CLIENT_ID env) will show
        // "not configured in this build yet" at sign-in time.
        if (BuildConfig.FIREBASE_API_KEY == null || BuildConfig.FIREBASE_API_KEY.isEmpty()) {
            android.util.Log.w("NightLightAuth", "FIREBASE_API_KEY empty: email/password + signup disabled in this build");
        }
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID == null || BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            android.util.Log.w("NightLightAuth", "GOOGLE_WEB_CLIENT_ID empty: Google sign-in disabled in this build");
        }
        // Kick off the media-session connection immediately (async, non-blocking).
        PlaybackManager playback = PlaybackManager.get(this);
        playback.setTrackStartedListener(track -> getLibraryRepository().recordPlay(track));

        // Backend warmup: Render free tier sleeps after idle (~35s cold start
        // measured), which used to land on the user's first tap. Ping trending
        // in the background at launch instead — it also pre-warms the Home
        // screen data. Retries ride through the wake window; everything is
        // silent and the normal on-demand paths retry anyway.
        AppExecutors.get().network().execute(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final boolean[] warmed = new boolean[1];
                try {
                    getMusicRepository().fetchTrending(new MusicRepository.TrendingCallback() {
                        @Override
                        public void onSuccess(com.nightlight.app.data.api.dto.TrendingDtos.TrendingDto data) {
                            warmed[0] = true;
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            latch.countDown();
                        }
                    });
                    latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                if (warmed[0]) {
                    break;
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });

        // Background sync: reconcile user data only for authenticated users.
        // Guests (no token) keep everything local: no server account is ever
        // created for them, and their playlists/likes never reach MongoDB.
        // Room shows local data instantly; this never blocks startup.
        if (TokenStore.hasToken()) {
            AppExecutors.get().network().execute(() -> {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                getLibraryRepository().syncFromServer();
                getPlaylistRepository().syncFromServer();
            });
        }
    }

    public static NightLightApp get() {
        return instance;
    }

    public LibraryRepository getLibraryRepository() {
        if (libraryRepository == null) {
            libraryRepository = new LibraryRepository(this);
        }
        return libraryRepository;
    }

    public PlaylistRepository getPlaylistRepository() {
        if (playlistRepository == null) {
            playlistRepository = new PlaylistRepository(this);
        }
        return playlistRepository;
    }

    public MusicRepository getMusicRepository() {
        if (musicRepository == null) {
            musicRepository = new MusicRepository(this);
        }
        return musicRepository;
    }

    public AuthRepository getAuthRepository() {
        if (authRepository == null) {
            authRepository = new AuthRepository(this);
        }
        return authRepository;
    }
}
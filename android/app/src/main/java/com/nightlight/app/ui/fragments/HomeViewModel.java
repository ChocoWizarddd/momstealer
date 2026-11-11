package com.nightlight.app.ui.fragments;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.api.dto.TrendingDtos;
import com.nightlight.app.data.api.dto.WeatherDtos;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.ContextEngine;

import java.util.ArrayList;
import java.util.List;

public class HomeViewModel extends AndroidViewModel {

    private static final long WEATHER_TTL_MS = 30 * 60 * 1000L;
    private static final long TRENDING_TTL_MS = 15 * 60 * 1000L;

    private final LibraryRepository library;
    private final PlaylistRepository playlists;
    private final MusicRepository music;

    private final androidx.lifecycle.LiveData<List<Track>> recentLiveData;
    private final MutableLiveData<WeatherDtos.WeatherDto> weather = new MutableLiveData<>();
    private final MutableLiveData<List<Track>> trendingSongs = new MutableLiveData<>();
    private final MutableLiveData<String> chartTitle = new MutableLiveData<>("");
    private final MutableLiveData<List<Track>> forYouTracks = new MutableLiveData<>();
    private final MutableLiveData<String> forYouTitle = new MutableLiveData<>("");
    private final MutableLiveData<String> forYouSubtitle = new MutableLiveData<>("");

    private long lastWeatherAt;
    private long lastTrendingAt;
    private boolean refreshing;
    private final androidx.lifecycle.Observer<List<Track>> recentsSeedObserver = tracks -> {
        // Once Room emits recents, seed the "For you" row (recents may not be
        // available the moment refreshContext() first runs).
        List<Track> forYou = forYouTracks.getValue();
        if (forYou == null || forYou.isEmpty()) {
            seedForYou();
        }
    };

    public HomeViewModel(@NonNull Application application) {
        super(application);
        NightLightApp app = (NightLightApp) application;
        this.library = app.getLibraryRepository();
        this.playlists = app.getPlaylistRepository();
        this.music = app.getMusicRepository();
        this.recentLiveData = library.observeRecent();
        publishContext();
        recentLiveData.observeForever(recentsSeedObserver);
    }

    @Override
    protected void onCleared() {
        recentLiveData.removeObserver(recentsSeedObserver);
        super.onCleared();
    }

    public LiveData<List<Track>> getRecent() {
        return recentLiveData;
    }

    public LiveData<List<Track>> getLikes() {
        return library.observeLikes();
    }

    public LiveData<List<Playlist>> getPlaylists() {
        return playlists.observePlaylists();
    }

    public LiveData<WeatherDtos.WeatherDto> getWeather() {
        return weather;
    }

    public LiveData<List<Track>> getTrendingSongs() {
        return trendingSongs;
    }

    public LiveData<String> getChartTitle() {
        return chartTitle;
    }

    public LiveData<List<Track>> getForYouTracks() {
        return forYouTracks;
    }

    public LiveData<String> getForYouTitle() {
        return forYouTitle;
    }

    public LiveData<String> getForYouSubtitle() {
        return forYouSubtitle;
    }

    /**
     * Loads context (weather + trending) and the "For you right now" row.
     * Guarded by TTLs so Home never hammers the backend; called once per Home
     * visit and again when the user picks a mood.
     *
     * Trending comes from the backend chart/trending endpoint (real chart songs,
     * chart title, trending albums). It is never filled from the generic
     * JioSaavn search fallback, so the trending row always shows a real chart
     * snapshot when the backend is reachable.
     */
    public synchronized void refreshContext() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        long now = System.currentTimeMillis();

        if (now - lastWeatherAt > WEATHER_TTL_MS) {
            lastWeatherAt = now;
            music.fetchWeather(new MusicRepository.WeatherCallback() {
                @Override
                public void onSuccess(WeatherDtos.WeatherDto w) {
                    weather.postValue(w);
                    publishContext();
                }

                @Override
                public void onFailure(Throwable error) {
                    // Weather is optional; Home keeps working with time + mood.
                    publishContext();
                }
            });
        }

        // Trending refresh is unbounded here: a swipe-to-refresh or a fresh
        // Home open should always re-fetch the latest chart snapshot, not only
        // when the app-wide TTL expired.
        lastTrendingAt = now;
        music.fetchTrending(new MusicRepository.TrendingCallback() {
            @Override
            public void onSuccess(TrendingDtos.TrendingDto trending) {
                List<Track> songs = new ArrayList<>();
                if (trending.songs != null) {
                    for (com.nightlight.app.data.api.dto.SongDtos.SongDto song : trending.songs) {
                        if (song != null && song.id != null) {
                            songs.add(Track.fromSong(song));
                        }
                    }
                }
                trendingSongs.postValue(songs);
                chartTitle.postValue(trending.chartTitle != null ? trending.chartTitle : "");
                // Once we have real trending songs we can also seed "For you".
                seedForYou();
            }

            @Override
            public void onFailure(Throwable error) {
                // No fabricated "trending": leave the section empty/hidden.
                seedForYou();
            }
        });
        seedForYou();
    }

    private boolean seedingForYou;

    /**
     * Rebuilds the "For You" row from the CURRENT mood/context: mood-first
     * seed (matches the active mood via keyword affinity), else most recent
     * listen, else trending. Radio supplies same-vibe candidates.
     */
    public void refreshForYou() {
        List<Track> recent = recentLiveData.getValue();
        List<Track> trending = trendingSongs.getValue();
        String mood = com.nightlight.app.util.AccountPrefs.effectiveMood(getApplication());
        Track seed = pickMoodSeed(recent, trending, mood);
        if (seed == null) {
            seed = pickMoodSeed(trending, trending, mood);
        }
        if (seed == null && recent != null && !recent.isEmpty()) {
            seed = recent.get(0);
        }
        if (seed == null && trending != null && !trending.isEmpty()) {
            seed = trending.get(0);
        }
        if (seed == null) {
            seedingForYou = false;
            return;
        }
        final Track fseed = seed;
        music.fetchRadio(fseed, 15, false, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                seedingForYou = false;
                if (tracks != null && !tracks.isEmpty()) {
                    forYouTracks.postValue(tracks);
                }
            }

            @Override
            public void onFailure(Throwable error) {
                seedingForYou = false;
                // Row keeps showing whatever it had; Home stays functional.
            }
        });
    }

    /** First track whose title/artist/album matches the mood keywords. */
    private static Track pickMoodSeed(List<Track> pool, List<Track> fallbackPool, String mood) {
        if (mood == null) {
            return (fallbackPool != null && !fallbackPool.isEmpty()) ? fallbackPool.get(0) : null;
        }
        if (pool == null) {
            return null;
        }
        String[] kws = com.nightlight.app.smartshuffle.ListeningContext.KEYWORDS.get(mood);
        if (kws == null) {
            return (fallbackPool != null && !fallbackPool.isEmpty()) ? fallbackPool.get(0) : null;
        }
        for (Track t : pool) {
            String hay = ((t.name != null ? t.name : "") + " "
                    + (t.artists != null ? t.artists : "") + " "
                    + (t.album != null ? t.album : "")).toLowerCase();
            for (String kw : kws) {
                if (hay.contains(kw)) {
                    return t;
                }
            }
        }
        return null;
    }

    /** Seeds the "For you" row from a track the user actually listens to. */
    private void seedForYou() {
        List<Track> existing = forYouTracks.getValue();
        if ((existing != null && !existing.isEmpty()) || seedingForYou) {
            return;
        }
        seedingForYou = true;
        List<Track> recent = recentLiveData.getValue();
        Track seed = null;
        if (recent != null && !recent.isEmpty()) {
            seed = recent.get(0);
        }
        if (seed == null) {
            List<Track> trending = trendingSongs.getValue();
            if (trending != null && !trending.isEmpty()) {
                seed = trending.get(0);
            }
        }
        if (seed == null) {
            seedingForYou = false; // allow a later retry once data arrives
            return;
        }
        music.fetchRadio(seed, 15, false, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                seedingForYou = false;
                if (tracks != null && !tracks.isEmpty()) {
                    forYouTracks.postValue(tracks);
                }
            }

            @Override
            public void onFailure(Throwable error) {
                seedingForYou = false;
                // Row stays hidden; Home is still fully functional.
            }
        });
    }

    /** Recomputes the contextual titles whenever weather or mood changes. */
    public void publishContext() {
        ContextEngine.Environment env = ContextEngine.build(getApplication(), weather.getValue());
        forYouTitle.postValue(env.title);
        forYouSubtitle.postValue(env.subtitle);
    }

    /** User picked a mood chip: persist + refresh context + re-rank now. */
    public void selectMood(String mood) {
        com.nightlight.app.util.MoodPrefs.set(getApplication(), mood);
        publishContext();
        refreshContext();
        // Immediate visible re-rank: rebuild "For You" around the new mood
        // (spec 11/12 — no restart, no spinner; current content stays until
        // the fresh batch arrives).
        refreshForYou();
    }

    public void clearMood() {
        com.nightlight.app.util.MoodPrefs.clear(getApplication());
        publishContext();
    }

    public String greeting() {
        return ContextEngine.greeting();
    }
}
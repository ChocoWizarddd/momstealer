package com.nightlight.app.data.repo;

import android.content.Context;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.MusicApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.LyricsDtos;
import com.nightlight.app.data.api.dto.SongDtos;
import com.nightlight.app.data.api.dto.TrendingDtos;
import com.nightlight.app.data.api.dto.WeatherDtos;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.util.AppExecutors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * All music-provider access. Search results include playable stream URLs;
 * library snapshots must be resolved via {@link #resolveTracks} before
 * playback (JioSaavn media URLs expire).
 */
public final class MusicRepository {

    public interface SearchCallback {
        void onSuccess(List<Track> tracks, int total, int page);

        void onFailure(Throwable error);
    }

    public interface TracksCallback {
        void onSuccess(List<Track> tracks);

        void onFailure(Throwable error);
    }

    public interface LyricsCallback {
        void onSuccess(LyricsDtos.LyricsDto lyrics);

        void onFailure(Throwable error);
    }

    public interface TrendingCallback {
        void onSuccess(TrendingDtos.TrendingDto trending);

        void onFailure(Throwable error);
    }

    public interface WeatherCallback {
        void onSuccess(WeatherDtos.WeatherDto weather);

        void onFailure(Throwable error);
    }

    private final Context app;
    private final MusicApi api;
    private final com.nightlight.app.data.api.NightLightApi nightLightApi;

    /** One in-flight song-detail fetch per id-list signature. */
    private final ConcurrentHashMap<String, List<TracksCallback>> pending = new ConcurrentHashMap<>();

    /** Short-lived radio batch cache (key = seed id) so toggling smart
     * shuffle again doesn't re-fetch the same related tracks. */
    private static final long RADIO_CACHE_TTL_MS = 30_000L;
    private String cachedRadioSeed;
    private List<Track> cachedRadioTracks;
    private long cachedRadioAt;
    private final AtomicBoolean radioCacheLock = new AtomicBoolean();

    private static final String TAG = "MusicRepository";

    /** Latest in-flight song search; cancelled when a newer query starts. */
    private final AtomicReference<retrofit2.Call<?>> currentSearchCall = new AtomicReference<>();

    /** Bounded page-0 search cache (normalized query → tracks). */
    private static final long SEARCH_CACHE_TTL_MS = 120_000L;
    private static final int SEARCH_CACHE_MAX_QUERIES = 20;

    private static final class SearchCacheEntry {
        final List<Track> tracks;
        final int total;
        final long at;

        SearchCacheEntry(List<Track> tracks, int total, long at) {
            this.tracks = tracks;
            this.total = total;
            this.at = at;
        }
    }

    private final java.util.LinkedHashMap<String, SearchCacheEntry> searchCache =
            new java.util.LinkedHashMap<String, SearchCacheEntry>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, SearchCacheEntry> eldest) {
                    return size() > SEARCH_CACHE_MAX_QUERIES;
                }
            };

    private synchronized SearchCacheEntry cachedSearch(String normalizedQuery) {
        return searchCache.get(normalizedQuery);
    }

    private synchronized void putSearchCache(String normalizedQuery, List<Track> tracks, int total) {
        searchCache.put(normalizedQuery, new SearchCacheEntry(new ArrayList<>(tracks), total, System.currentTimeMillis()));
    }

    private synchronized void removeSearchCache(String normalizedQuery) {
        searchCache.remove(normalizedQuery);
    }

    /** Cancels any in-flight song search (e.g. query cleared). */
    public void cancelSearch() {
        retrofit2.Call<?> stale = currentSearchCall.getAndSet(null);
        if (stale != null) {
            stale.cancel();
        }
    }

    public MusicRepository(Context context) {
        this.app = context.getApplicationContext();
        this.api = ApiClient.musicApi(app);
        this.nightLightApi = ApiClient.nightLightApi(app);
    }

    /** Maps a provider song — always prefers 320kbps (single balanced profile). */
    private Track mapSong(SongDtos.SongDto song) {
        return Track.fromSong(song, true);
    }

    public void searchSongs(String query, int page, int limit, SearchCallback callback) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        // Bounded page-0 cache: repeat searches (back-navigation, retries,
        // retyping) skip the network. Paged continuations always fetch.
        if (page == 0) {
            SearchCacheEntry cached = cachedSearch(normalized);
            if (cached != null && System.currentTimeMillis() - cached.at < SEARCH_CACHE_TTL_MS) {
                android.util.Log.d(TAG, "search cache hit qlen=" + normalized.length());
                callback.onSuccess(new java.util.ArrayList<>(cached.tracks), cached.total, page);
                return;
            } else if (cached != null) {
                removeSearchCache(normalized);
            }
        }
        // Cancel the previous in-flight search: only the latest query may
        // update the UI (the ViewModel also ignores stale responses).
        retrofit2.Call<?> stale = currentSearchCall.getAndSet(null);
        if (stale != null) {
            stale.cancel();
        }
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        final int requestPage = page;
        retrofit2.Call<ApiResponse<SongDtos.SearchSongsDto>> call = api.searchSongs(query, page, limit);
        currentSearchCall.set(call);
        call.enqueue(new Callback<ApiResponse<SongDtos.SearchSongsDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<SongDtos.SearchSongsDto>> call,
                                   Response<ApiResponse<SongDtos.SearchSongsDto>> response) {
                currentSearchCall.compareAndSet(call, null);
                android.util.Log.d(TAG, "search network page=" + requestPage + " ms="
                        + (android.os.SystemClock.elapsedRealtime() - startedAt));
                ApiResponse<SongDtos.SearchSongsDto> body = response.body();
                if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                    callback.onFailure(new HttpStatusException(response.code(), body != null ? body.code : null));
                    return;
                }
                List<Track> tracks = new ArrayList<>();
                if (body.data.results != null) {
                    for (SongDtos.SongDto song : body.data.results) {
                        if (song.id != null) {
                            tracks.add(mapSong(song));
                        }
                    }
                }
                if (requestPage == 0 && response.isSuccessful()) {
                    putSearchCache(normalized, tracks, body.data.total);
                }
                callback.onSuccess(tracks, body.data.total, requestPage);
            }

            @Override
            public void onFailure(Call<ApiResponse<SongDtos.SearchSongsDto>> call, Throwable t) {
                currentSearchCall.compareAndSet(call, null);
                callback.onFailure(t);
            }
        });
    }

    /**
     * Fetches a radio batch of OTHER songs related to the seed track. Used for
     * auto-next when a queue ends and for same-genre shuffle top-ups.
     *
     * @param useCache when true, a fresh short-lived cached batch for the same
     *                 seed+limit may be served instead of hitting the network
     *                 (smart-shuffle re-toggles); auto-continue passes false.
     */
    public void fetchRadio(Track seed, int limit, boolean useCache, SearchCallback callback) {
        if (seed == null || seed.id == null) {
            AppExecutors.onMain(() -> callback.onFailure(new IllegalStateException("No seed track")));
            return;
        }
        String cacheKey = seed.id + ":" + limit;
        if (useCache
                && cacheKey.equals(cachedRadioSeed)
                && cachedRadioTracks != null
                && !cachedRadioTracks.isEmpty()
                && System.currentTimeMillis() - cachedRadioAt < RADIO_CACHE_TTL_MS) {
            List<Track> hit = new ArrayList<>(cachedRadioTracks);
            AppExecutors.onMain(() -> callback.onSuccess(hit, hit.size(), 0));
            return;
        }
        api.getRadio(seed.id, seed.name, seed.artists, seed.album, limit)
                .enqueue(new Callback<ApiResponse<SongDtos.SearchSongsDto>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<SongDtos.SearchSongsDto>> call,
                                           Response<ApiResponse<SongDtos.SearchSongsDto>> response) {
                        ApiResponse<SongDtos.SearchSongsDto> body = response.body();
                        if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                            callback.onFailure(new HttpStatusException(response.code(), body != null ? body.code : null));
                            return;
                        }
                        List<Track> tracks = new ArrayList<>();
                        if (body.data.results != null) {
                            for (SongDtos.SongDto song : body.data.results) {
                                if (song.id != null) {
                                    tracks.add(mapSong(song));
                                }
                            }
                        }
                        if (radioCacheLock.compareAndSet(false, true)) {
                            cachedRadioSeed = cacheKey;
                            cachedRadioTracks = tracks;
                            cachedRadioAt = System.currentTimeMillis();
                            radioCacheLock.set(false);
                        }
                        callback.onSuccess(tracks, body.data.total, 0);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<SongDtos.SearchSongsDto>> call, Throwable t) {
                        callback.onFailure(t);
                    }
                });
    }

    /**
     * Real trending data from the backend (JioSaavn charts). Cache is handled
     * server-side; this is safe to call once per Home open.
     */
    public void fetchTrending(TrendingCallback callback) {
        api.getTrending().enqueue(new Callback<ApiResponse<TrendingDtos.TrendingDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<TrendingDtos.TrendingDto>> call,
                                   Response<ApiResponse<TrendingDtos.TrendingDto>> response) {
                ApiResponse<TrendingDtos.TrendingDto> body = response.body();
                if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                    callback.onFailure(new HttpStatusException(response.code(),
                            body != null ? body.code : null));
                    return;
                }
                callback.onSuccess(body.data);
            }

            @Override
            public void onFailure(Call<ApiResponse<TrendingDtos.TrendingDto>> call, Throwable t) {
                callback.onFailure(t);
            }
        });
    }

    /** Server-side weather (Open-Meteo proxied; no keys in the app). */
    public void fetchWeather(WeatherCallback callback) {
        nightLightApi.getWeather().enqueue(new Callback<ApiResponse<WeatherDtos.WeatherDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<WeatherDtos.WeatherDto>> call,
                                   Response<ApiResponse<WeatherDtos.WeatherDto>> response) {
                ApiResponse<WeatherDtos.WeatherDto> body = response.body();
                if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                    callback.onFailure(new HttpStatusException(response.code(),
                            body != null ? body.code : null));
                    return;
                }
                callback.onSuccess(body.data);
            }

            @Override
            public void onFailure(Call<ApiResponse<WeatherDtos.WeatherDto>> call, Throwable t) {
                callback.onFailure(t);
            }
        });
    }

    /** Synchronized lyrics for the current track (server-resolved). */
    /**
     * Lyrics cache keyed by STABLE TRACK ID (never title — two different songs
     * can share a title). Bounded so a long session cannot accumulate unbounded
     * entries; a track-change race is impossible because the key is the id the
     * UI already filters on.
     */
    private final java.util.Map<String, LyricsDtos.LyricsDto> lyricsCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, LyricsDtos.LyricsDto>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LyricsDtos.LyricsDto> eldest) {
                    return size() > 40;
                }
            });

    public void fetchLyrics(Track track, LyricsCallback callback) {
        if (track != null && track.id != null) {
            LyricsDtos.LyricsDto hit = lyricsCache.get(track.id);
            if (hit != null) {
                AppExecutors.onMain(() -> callback.onSuccess(hit));
                return;
            }
        }
        api.getLyrics(track.name, track.artists, track.album, track.durationMs)
                .enqueue(new Callback<ApiResponse<LyricsDtos.LyricsDto>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<LyricsDtos.LyricsDto>> call,
                                           Response<ApiResponse<LyricsDtos.LyricsDto>> response) {
                        ApiResponse<LyricsDtos.LyricsDto> body = response.body();
                        if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                            callback.onFailure(new HttpStatusException(response.code(),
                                    body != null ? body.code : null));
                            return;
                        }
                        if (track != null && track.id != null && body.data != null && body.data.available) {
                            lyricsCache.put(track.id, body.data);
                        }
                        callback.onSuccess(body.data);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<LyricsDtos.LyricsDto>> call, Throwable t) {
                        callback.onFailure(t);
                    }
                });
    }

    /**
     * Fetches fresh song details (including stream URLs) for a list of track
     * ids. Batches ids into single requests; dedupes concurrent requests.
     */
    public void getTracksByIds(List<String> ids, TracksCallback callback) {
        List<String> unique = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !unique.contains(id)) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            AppExecutors.onMain(() -> callback.onSuccess(new ArrayList<>()));
            return;
        }

        String key = String.join(",", unique);
        List<TracksCallback> waiters = pending.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (waiters) {
            waiters.add(callback);
            if (waiters.size() > 1) {
                // Another request for the same ids is in flight.
                return;
            }
        }

        fetchChunk(unique, key, 0, new ArrayList<>());
    }

    private void fetchChunk(List<String> ids, String key, int from, List<Track> accumulated) {
        int to = Math.min(from + 20, ids.size());
        List<String> chunk = ids.subList(from, to);

        api.getSongs(String.join(",", chunk)).enqueue(new Callback<ApiResponse<List<SongDtos.SongDto>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<SongDtos.SongDto>>> call,
                                   Response<ApiResponse<List<SongDtos.SongDto>>> response) {
                ApiResponse<List<SongDtos.SongDto>> body = response.body();
                if (!response.isSuccessful() || body == null || !body.success || body.data == null) {
                    deliverError(key, new HttpStatusException(response.code(), body != null ? body.code : null));
                    return;
                }
                for (SongDtos.SongDto song : body.data) {
                    if (song.id != null) {
                        accumulated.add(mapSong(song));
                    }
                }
                if (to < ids.size()) {
                    fetchChunk(ids, key, to, accumulated);
                } else {
                    deliverSuccess(key, new ArrayList<>(accumulated));
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<List<SongDtos.SongDto>>> call, Throwable t) {
                deliverError(key, t);
            }
        });
    }

    /** Converts library snapshots into playable tracks (fresh stream URLs). */
    public void resolveTracks(List<Track> snapshots, TracksCallback callback) {
        List<String> ids = new ArrayList<>();
        for (Track t : snapshots) {
            ids.add(t.id);
        }
        getTracksByIds(ids, new TracksCallback() {
            @Override
            public void onSuccess(List<Track> fresh) {
                // Rebuild in the original order so queues stay consistent.
                java.util.Map<String, Track> byId = new java.util.HashMap<>();
                for (Track t : fresh) {
                    byId.put(t.id, t);
                }
                List<Track> ordered = new ArrayList<>();
                for (Track original : snapshots) {
                    Track resolved = byId.get(original.id);
                    ordered.add(resolved != null ? resolved : original);
                }
                callback.onSuccess(ordered);
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    private void deliverSuccess(String key, List<Track> tracks) {
        List<TracksCallback> waiters = pending.remove(key);
        if (waiters == null) {
            return;
        }
        synchronized (waiters) {
            for (TracksCallback c : waiters) {
                AppExecutors.onMain(() -> c.onSuccess(tracks));
            }
        }
    }

    private void deliverError(String key, Throwable error) {
        List<TracksCallback> waiters = pending.remove(key);
        if (waiters == null) {
            return;
        }
        synchronized (waiters) {
            for (TracksCallback c : waiters) {
                AppExecutors.onMain(() -> c.onFailure(error));
            }
        }
    }

    public static final class HttpStatusException extends Exception {
        public final int status;
        /** Server error code (e.g. RATE_LIMITED, SEARCH_UNAVAILABLE), may be null. */
        public final String code;

        public HttpStatusException(int status, String code) {
            super("HTTP " + status + (code != null ? " (" + code + ")" : ""));
            this.status = status;
            this.code = code;
        }
    }
}
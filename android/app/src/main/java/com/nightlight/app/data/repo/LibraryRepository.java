package com.nightlight.app.data.repo;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.Transformations;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.data.db.AppDatabase;
import com.nightlight.app.data.db.entity.LikedTrackEntity;
import com.nightlight.app.data.db.entity.RecentTrackEntity;
import com.nightlight.app.data.db.entity.SearchHistoryEntity;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Likes, recently played, search history and preferences. Room is the local
 * source of truth; server writes are best-effort and reconciled by
 * {@link #syncFromServer} at startup.
 */
public final class LibraryRepository {

    public interface SimpleCallback {
        void onDone(boolean success);
    }

    private final Context app;
    private final AppDatabase db;
    private final NightLightApi api;
    /**
     * Small in-memory cache of liked track ids for recommendation scoring
     * (familiarity bonus). Kept in sync by {@link #toggleLike} and
     * {@link #syncLikes} so Smart Shuffle never hits Room per request.
     */
    private volatile Set<String> likedIdCache = new HashSet<>();

    public LibraryRepository(Context context) {
        this.app = context.getApplicationContext();
        this.db = AppDatabase.get(app);
        this.api = ApiClient.nightLightApi(app);
        // Warm the cache in the background so it is ready by first radio fetch.
        AppExecutors.get().io().execute(() -> {
            List<LikedTrackEntity> likes = db.libraryDao().getLikes(1000);
            Set<String> ids = new HashSet<>();
            for (LikedTrackEntity e : likes) {
                ids.add(e.trackId);
            }
            likedIdCache = ids;
        });
    }

    /** Cached liked ids (non-blocking; may briefly lag behind toggles). */
    public Set<String> likedIds() {
        return new HashSet<>(likedIdCache);
    }

    /** Snapshot of full liked + recent tracks for recommendation pools. */
    public interface RecoSnapshotCallback {
        void onReady(List<Track> liked, List<Track> recent);
    }

    /**
     * Reads liked (cap 200) + recent (cap 30) off the main thread for the
     * recommendation candidate pool. Never blocks playback or networking.
     */
    public void snapshotForReco(RecoSnapshotCallback callback) {
        AppExecutors.get().io().execute(() -> {
            List<Track> liked = new ArrayList<>();
            List<Track> recent = new ArrayList<>();
            try {
                List<LikedTrackEntity> likes = db.libraryDao().getLikes(200);
                for (LikedTrackEntity e : likes) liked.add(Track.fromEntity(e));
                List<RecentTrackEntity> recents = db.libraryDao().getAllRecent();
                for (int i = 0; i < Math.min(30, recents.size()); i++) {
                    recent.add(Track.fromEntity(recents.get(i)));
                }
            } catch (Exception ignored) {
                // Local-only enrichment: failure yields empty pools, never an error.
            }
            final List<Track> fLiked = liked;
            final List<Track> fRecent = recent;
            AppExecutors.onMain(() -> callback.onReady(fLiked, fRecent));
        });
    }

    // ---- Observables ----

    public LiveData<List<Track>> observeLikes() {
        LiveData<List<LikedTrackEntity>> source = db.libraryDao().observeLikes();
        return Transformations.map(source, entities -> {
            List<Track> tracks = new ArrayList<>();
            for (LikedTrackEntity e : entities) {
                tracks.add(Track.fromEntity(e));
            }
            return tracks;
        });
    }

    public LiveData<List<Track>> observeRecent() {
        LiveData<List<RecentTrackEntity>> source = db.libraryDao().observeRecent(30);
        return Transformations.map(source, entities -> {
            List<Track> tracks = new ArrayList<>();
            for (RecentTrackEntity e : entities) {
                tracks.add(Track.fromEntity(e));
            }
            return tracks;
        });
    }

    public LiveData<List<String>> observeHistoryQueries() {
        LiveData<List<SearchHistoryEntity>> source = db.libraryDao().observeHistory(20);
        return Transformations.map(source, entities -> {
            List<String> queries = new ArrayList<>();
            for (SearchHistoryEntity e : entities) {
                queries.add(e.query);
            }
            return queries;
        });
    }

    // ---- Likes ----

    public boolean isLiked(String trackId) {
        return db.libraryDao().getLike(trackId) != null;
    }

    /** Optimistic like: Room updates immediately, server write is async. */
    public void toggleLike(Track track, boolean like, SimpleCallback callback) {
        AppExecutors.get().io().execute(() -> {
            if (like) {
                db.libraryDao().insertLike(LikedTrackEntity.from(
                        track.id, track.name, track.artists, track.album, track.imageUrl,
                        track.streamUrl, track.durationMs, track.year, System.currentTimeMillis()));
            } else {
                db.libraryDao().deleteLikeById(track.id);
            }
            if (like) {
                likedIdCache.add(track.id);
            } else {
                likedIdCache.remove(track.id);
            }
            AppExecutors.onMain(() -> {
                if (callback != null) {
                    callback.onDone(true);
                }
            });
            // Explicit feedback for the taste profile (strongest signal).
            try {
                com.nightlight.app.player.PlaybackManager.get(app).recordSignal(
                        track, like ? com.nightlight.app.reco.Signal.LIKE
                                : com.nightlight.app.reco.Signal.UNLIKE);
            } catch (Exception ignored) {
                // Feedback must never break the like toggle itself.
            }
        });
        pushLike(track, like);
    }

    private void pushLike(Track track, boolean like) {
        if (!TokenStore.hasToken()) {
            return;
        }
        Call<ApiResponse<Object>> call;
        if (like) {
            call = api.likeTrack(track.id, new Requests.LikeRequest(track.toSnapshot()));
        } else {
            call = api.unlikeTrack(track.id);
        }
        call.enqueue(new Callback<ApiResponse<Object>>() {
            @Override
            public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
                // Ignored: Room is authoritative locally; sync reconciles.
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
                // Ignored: reconciled on next successful sync.
            }
        });
    }

    // ---- Recently played ----

    public void recordPlay(Track track) {
        if (track == null || track.id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        AppExecutors.get().io().execute(() -> {
            RecentTrackEntity entity = new RecentTrackEntity();
            entity.trackId = track.id;
            entity.name = track.name;
            entity.artists = track.artists;
            entity.album = track.album;
            entity.imageUrl = track.imageUrl;
            entity.streamUrl = track.streamUrl;
            entity.duration = track.durationMs;
            entity.year = track.year;
            entity.createdAt = now;
            db.libraryDao().upsertRecent(entity);
            // Bound local history so it cannot grow forever.
            List<RecentTrackEntity> all = db.libraryDao().getAllRecent();
            for (int i = 50; i < all.size(); i++) {
                db.libraryDao().deleteRecentById(all.get(i).trackId);
            }
        });
        pushPlay(track);
    }

    private void pushPlay(Track track) {
        if (!TokenStore.hasToken()) {
            return;
        }
        api.recordPlay(new Requests.LikeRequest(track.toSnapshot())).enqueue(new Callback<ApiResponse<Object>>() {
            @Override
            public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
            }
        });
    }

    /**
     * Reports a threshold-crossed play for NightLight-owned popularity.
     * Fire-and-forget, auth-gated (guests skip); the server enforces the
     * threshold again and dedupes repeats, so duplicate calls are harmless.
     */
    public void reportPlayPosition(String trackId, String title, java.util.List<String> artists,
                                   long positionMs, long durationMs) {
        if (!com.nightlight.app.util.TokenStore.hasToken()
                || trackId == null || title == null || title.trim().isEmpty()) {
            return;
        }
        api.recordPlayEvent(new Requests.PlayEventRequest(
                trackId, title, artists != null ? artists : new java.util.ArrayList<>(),
                positionMs, durationMs)).enqueue(new Callback<ApiResponse<Object>>() {
            @Override
            public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
            }

            @Override
            public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
            }
        });
    }

    public void clearRecent() {
        AppExecutors.get().io().execute(() -> db.libraryDao().clearRecent());
    }

    // ---- Search history ----

    public void addSearchHistory(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        String trimmed = query.trim();
        long now = System.currentTimeMillis();
        AppExecutors.get().io().execute(() -> {
            // Dedupe: move an existing identical query to the top.
            List<SearchHistoryEntity> existing = db.libraryDao().getHistory(100);
            for (SearchHistoryEntity e : existing) {
                if (e.query.equalsIgnoreCase(trimmed)) {
                    db.libraryDao().deleteHistoryById(e.id);
                    break;
                }
            }
            db.libraryDao().insertHistory(SearchHistoryEntity.from(trimmed, now));
        });
        if (TokenStore.hasToken()) {
            api.addSearchHistory(new Requests.SearchHistoryRequest(trimmed)).enqueue(new Callback<ApiResponse<UserDtos.HistoryDto>>() {
                @Override
                public void onResponse(Call<ApiResponse<UserDtos.HistoryDto>> c, Response<ApiResponse<UserDtos.HistoryDto>> r) {
                }

                @Override
                public void onFailure(Call<ApiResponse<UserDtos.HistoryDto>> c, Throwable t) {
                }
            });
        }
    }

    public void deleteSearchHistory(long id) {
        AppExecutors.get().io().execute(() -> {
            SearchHistoryEntity e = new SearchHistoryEntity();
            e.id = id;
            db.libraryDao().deleteHistory(e);
        });
    }

    public void deleteSearchHistoryQuery(String query) {
        AppExecutors.get().io().execute(() -> {
            List<SearchHistoryEntity> all = db.libraryDao().getHistory(100);
            for (SearchHistoryEntity e : all) {
                if (e.query.equalsIgnoreCase(query)) {
                    db.libraryDao().deleteHistoryById(e.id);
                }
            }
        });
        if (TokenStore.hasToken()) {
            // Best effort server-side delete of the matching entry.
            api.getSearchHistory(30).enqueue(new Callback<ApiResponse<UserDtos.HistoryListDto>>() {
                @Override
                public void onResponse(Call<ApiResponse<UserDtos.HistoryListDto>> c, Response<ApiResponse<UserDtos.HistoryListDto>> r) {
                    ApiResponse<UserDtos.HistoryListDto> body = r.body();
                    if (r.isSuccessful() && body != null && body.success && body.data != null
                            && body.data.items != null) {
                        for (UserDtos.HistoryDto item : body.data.items) {
                            if (query.equalsIgnoreCase(item.query)) {
                                api.deleteSearchHistory(item.id).enqueue(new Callback<ApiResponse<Object>>() {
                                    @Override
                                    public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
                                    }

                                    @Override
                                    public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
                                    }
                                });
                                break;
                            }
                        }
                    }
                }

                @Override
                public void onFailure(Call<ApiResponse<UserDtos.HistoryListDto>> c, Throwable t) {
                }
            });
        }
    }

    public void clearSearchHistory() {
        AppExecutors.get().io().execute(() -> db.libraryDao().clearHistory());
        if (TokenStore.hasToken()) {
            api.clearSearchHistory().enqueue(new Callback<ApiResponse<Object>>() {
                @Override
                public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
                }

                @Override
                public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
                }
            });
        }
    }

    // ---- Preferences ----

    public int getRepeatPref() {
        String v = db.libraryDao().getPreference("repeatMode");
        return v != null ? Integer.parseInt(v) : 0;
    }

    public boolean getShufflePref() {
        return "1".equals(db.libraryDao().getPreference("shuffle"));
    }

    public void setRepeatPref(int mode) {
        AppExecutors.get().io().execute(() ->
                db.libraryDao().putPreference(com.nightlight.app.data.db.entity.PreferenceEntity.of("repeatMode", String.valueOf(mode))));
    }

    public void setShufflePref(boolean shuffle) {
        AppExecutors.get().io().execute(() ->
                db.libraryDao().putPreference(com.nightlight.app.data.db.entity.PreferenceEntity.of("shuffle", shuffle ? "1" : "0")));
    }

    // ---- Sync ----

    /**
     * Pulls server state and merges it into Room without clobbering local
     * offline writes: likes push both ways, history merges by query, recent
     * plays merge by track. Runs on background threads.
     */
    public void syncFromServer() {
        if (!TokenStore.hasToken()) {
            return;
        }
        syncLikes();
        syncRecent();
        syncHistory();
    }

    private void syncLikes() {
        api.getLikedIds().enqueue(new Callback<ApiResponse<UserDtos.LikedIdsDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.LikedIdsDto>> c, Response<ApiResponse<UserDtos.LikedIdsDto>> r) {
                ApiResponse<UserDtos.LikedIdsDto> body = r.body();
                if (!r.isSuccessful() || body == null || !body.success || body.data == null) {
                    return;
                }
                Set<String> serverIds = new HashSet<>(body.data.ids);
                likedIdCache = new HashSet<>(serverIds);
                AppExecutors.get().io().execute(() -> {
                    List<LikedTrackEntity> local = db.libraryDao().getLikes(1000);
                    Map<String, LikedTrackEntity> localMap = new HashMap<>();
                    for (LikedTrackEntity e : local) {
                        localMap.put(e.trackId, e);
                    }
                    // Push local likes the server doesn't know about.
                    for (LikedTrackEntity e : local) {
                        if (!serverIds.contains(e.trackId)) {
                            pushLike(Track.fromEntity(e), true);
                        }
                    }
                    // Remove server-unliked tracks that were never liked locally? No:
                    // local is truth — only prune what the server removed AND
                    // wasn't re-liked locally. Keep it simple: leave local rows.
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.LikedIdsDto>> c, Throwable t) {
            }
        });
    }

    private void syncRecent() {
        api.getRecentlyPlayed(30).enqueue(new Callback<ApiResponse<UserDtos.RecentListDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.RecentListDto>> c, Response<ApiResponse<UserDtos.RecentListDto>> r) {
                ApiResponse<UserDtos.RecentListDto> body = r.body();
                if (!r.isSuccessful() || body == null || !body.success || body.data == null
                        || body.data.items == null) {
                    return;
                }
                AppExecutors.get().io().execute(() -> {
                    for (UserDtos.RecentDto item : body.data.items) {
                        if (item.track == null || item.track.id == null) {
                            continue;
                        }
                        RecentTrackEntity e = new RecentTrackEntity();
                        e.trackId = item.track.id;
                        e.name = item.track.name;
                        e.artists = item.track.artists != null ? item.track.artists : "";
                        e.album = item.track.album != null ? item.track.album : "";
                        e.imageUrl = item.track.imageUrl != null ? item.track.imageUrl : "";
                        e.duration = item.track.duration != null ? item.track.duration : 0L;
                        e.year = item.track.year != null ? item.track.year : "";
                        e.createdAt = parseTime(item.playedAt);
                        db.libraryDao().upsertRecent(e);
                    }
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.RecentListDto>> c, Throwable t) {
            }
        });
    }

    private void syncHistory() {
        api.getSearchHistory(30).enqueue(new Callback<ApiResponse<UserDtos.HistoryListDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.HistoryListDto>> c, Response<ApiResponse<UserDtos.HistoryListDto>> r) {
                ApiResponse<UserDtos.HistoryListDto> body = r.body();
                if (!r.isSuccessful() || body == null || !body.success || body.data == null
                        || body.data.items == null) {
                    return;
                }
                AppExecutors.get().io().execute(() -> {
                    Set<String> known = new HashSet<>();
                    for (UserDtos.HistoryDto item : body.data.items) {
                        if (known.add(item.query)) {
                            db.libraryDao().insertHistory(SearchHistoryEntity.from(item.query, parseTime(item.createdAt)));
                        }
                    }
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.HistoryListDto>> c, Throwable t) {
            }
        });
    }

    private static long parseTime(String iso) {
        if (iso == null) {
            return System.currentTimeMillis();
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(iso);
            return instant.toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
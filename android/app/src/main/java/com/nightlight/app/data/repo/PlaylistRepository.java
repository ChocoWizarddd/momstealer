package com.nightlight.app.data.repo;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.ImportDtos;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.SongDtos;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.data.db.AppDatabase;
import com.nightlight.app.data.db.entity.PlaylistEntity;
import com.nightlight.app.data.db.entity.PlaylistTrackEntity;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Playlists are stored in Room immediately (local-first, offline-capable) and
 * mirrored to the server. Unsynced local playlists are pushed during sync.
 */
public final class PlaylistRepository {

    public interface ActionCallback {
        void onDone(boolean success);
    }

    /** Result of a Spotify/YouTube playlist conversion. */
    public interface ImportCallback {
        void onSuccess(String playlistName, List<Track> tracks, List<String> unmatched);

        void onFailure(Throwable error);
    }

    /** Live import progress: done/total track counts plus the current title. */
    public interface ImportProgressCallback extends ImportCallback {
        void onProgress(int done, int total, String currentTitle);

        /** Full source size when known (Spotify total); -1 otherwise. */
        default void onSourceTotal(int sourceTotal) {}
    }

    public interface IdCallback {
        void onDone(String playlistId, boolean success);
    }

    private final Context app;
    private final AppDatabase db;
    private final NightLightApi api;

    public PlaylistRepository(Context context) {
        this.app = context.getApplicationContext();
        this.db = AppDatabase.get(app);
        this.api = ApiClient.nightLightApi(app);
    }

    public LiveData<List<Playlist>> observePlaylists() {
        LiveData<List<PlaylistEntity>> source = db.playlistDao().observePlaylists();
        return Transformations.map(source, entities -> {
            List<Playlist> playlists = new ArrayList<>();
            for (PlaylistEntity e : entities) {
                playlists.add(Playlist.fromEntity(e));
            }
            return playlists;
        });
    }

    public List<Playlist> getPlaylistsSync() {
        List<Playlist> result = new ArrayList<>();
        for (PlaylistEntity e : db.playlistDao().getPlaylists()) {
            result.add(Playlist.fromEntity(e));
        }
        return result;
    }

    /** Creates locally (offline-safe); mirrors to the server when possible. */
    public void createPlaylist(String name, ActionCallback callback) {
        createPlaylistWithId(name, (id, ok) -> callback.onDone(ok));
    }

    public void createPlaylistWithId(String name, IdCallback callback) {
        long now = System.currentTimeMillis();
        String localId = "local-" + UUID.randomUUID();
        AppExecutors.get().io().execute(() -> {
            db.playlistDao().insertPlaylist(PlaylistEntity.local(localId, name, now));
            AppExecutors.onMain(() -> callback.onDone(localId, true));
        });
        if (TokenStore.hasToken()) {
            api.createPlaylist(new Requests.PlaylistCreateRequest(name)).enqueue(new Callback<ApiResponse<UserDtos.PlaylistDetailDto>>() {
                @Override
                public void onResponse(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c,
                                       Response<ApiResponse<UserDtos.PlaylistDetailDto>> r) {
                    ApiResponse<UserDtos.PlaylistDetailDto> body = r.body();
                    if (!r.isSuccessful() || body == null || !body.success || body.data == null
                            || body.data.playlist == null) {
                        return; // stays local, pushed later by sync
                    }
                    UserDtos.PlaylistDto server = body.data.playlist;
                    AppExecutors.get().io().execute(() -> {
                        PlaylistEntity local = db.playlistDao().getPlaylist(localId);
                        if (local == null) {
                            return;
                        }
                        db.playlistDao().deletePlaylist(local);
                        db.playlistDao().insertPlaylist(PlaylistEntity.synced(
                                server.id, server.name, server.description, server.artworkUrl,
                                server.trackCount,
                                parseTime(server.createdAt), parseTime(server.updatedAt)));
                        // The server copy has no tracks yet; nothing to migrate.
                    });
                }

                @Override
                public void onFailure(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c, Throwable t) {
                }
            });
        }
    }

    public void renamePlaylist(String playlistId, String name) {
        AppExecutors.get().io().execute(() -> {
            PlaylistEntity e = db.playlistDao().getPlaylist(playlistId);
            if (e != null) {
                e.name = name;
                e.updatedAt = System.currentTimeMillis();
                db.playlistDao().updatePlaylist(e);
            }
        });
        if (TokenStore.hasToken() && !playlistId.startsWith("local-")) {
            api.updatePlaylist(playlistId, new Requests.PlaylistUpdateRequest(name))
                    .enqueue(new Callback<ApiResponse<UserDtos.PlaylistDetailDto>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c,
                                               Response<ApiResponse<UserDtos.PlaylistDetailDto>> r) {
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c, Throwable t) {
                        }
                    });
        }
    }

    public void deletePlaylist(String playlistId, ActionCallback callback) {
        AppExecutors.get().io().execute(() -> {
            PlaylistEntity e = db.playlistDao().getPlaylist(playlistId);
            if (e != null) {
                db.playlistDao().deletePlaylist(e);
            }
            db.playlistDao().clearTracks(playlistId);
            AppExecutors.onMain(() -> callback.onDone(true));
        });
        if (TokenStore.hasToken() && !playlistId.startsWith("local-")) {
            api.deletePlaylist(playlistId).enqueue(new Callback<ApiResponse<Object>>() {
                @Override
                public void onResponse(Call<ApiResponse<Object>> c, Response<ApiResponse<Object>> r) {
                }

                @Override
                public void onFailure(Call<ApiResponse<Object>> c, Throwable t) {
                }
            });
        }
    }

    /**
     * Converts a public Spotify/YouTube playlist URL into playable library
     * tracks (matched server-side). Callbacks arrive on the main thread.
     */
    public void importFromUrl(String url, int limit, final ImportCallback callback) {
        if (callback instanceof ImportProgressCallback) {
            importFromUrlStreaming(url, limit, (ImportProgressCallback) callback);
            return;
        }
        importFromUrlSingleShot(url, limit, callback);
    }

    /** Single-shot conversion without progress events (fallback + legacy path). */
    private void importFromUrlSingleShot(String url, int limit, final ImportCallback callback) {
        api.importPlaylist(new Requests.ImportRequest(url, limit))
                .enqueue(new Callback<ApiResponse<ImportDtos.ImportResultDto>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<ImportDtos.ImportResultDto>> c,
                                           Response<ApiResponse<ImportDtos.ImportResultDto>> r) {
                        ApiResponse<ImportDtos.ImportResultDto> body = r.body();
                        if (!r.isSuccessful() || body == null || !body.success || body.data == null) {
                            callback.onFailure(new MusicRepository.HttpStatusException(
                                    r.code(), body != null ? body.code : null));
                            return;
                        }
                        List<Track> tracks = new ArrayList<>();
                        if (body.data.results != null) {
                            for (SongDtos.SongDto song : body.data.results) {
                                if (song.id != null) {
                                    tracks.add(Track.fromSong(song));
                                }
                            }
                        }
                        List<String> unmatched = body.data.unmatched != null ? body.data.unmatched
                                : new ArrayList<String>();
                        callback.onSuccess(body.data.playlistName, tracks, unmatched);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<ImportDtos.ImportResultDto>> c, Throwable t) {
                        callback.onFailure(t);
                    }
                });
    }

    /**
     * Streaming import: reads NDJSON progress events so the UI can show
     * "12 / 87 songs" live instead of a static spinner. Falls back to the
     * single-shot endpoint if streaming fails.
     */
    public void importFromUrlStreaming(String url, int limit, final ImportProgressCallback callback) {
        AppExecutors.get().io().execute(() -> {
            try {
                retrofit2.Response<okhttp3.ResponseBody> resp =
                        api.importPlaylistStream(new Requests.ImportRequest(url, limit)).execute();
                okhttp3.ResponseBody body = resp.body();
                if (!resp.isSuccessful() || body == null) {
                    throw new java.io.IOException("stream failed: " + resp.code());
                }
                org.json.JSONObject donePayload = null;
                try (okio.BufferedSource source = body.source()) {
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null) break;
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        org.json.JSONObject evt;
                        try {
                            evt = new org.json.JSONObject(line);
                        } catch (org.json.JSONException ignored) {
                            continue;
                        }
                        String type = evt.optString("type", "");
                        if ("progress".equals(type)) {
                            int done = evt.optInt("done", 0);
                            int total = evt.optInt("total", 0);
                            String title = evt.optString("currentTitle", "");
                            AppExecutors.onMain(() -> callback.onProgress(done, total, title));
                        } else if ("done".equals(type)) {
                            donePayload = evt.optJSONObject("data");
                            break;
                        } else if ("error".equals(type)) {
                            String msg = evt.optString("message", "Import failed");
                            throw new java.io.IOException(msg);
                        }
                    }
                }
                if (donePayload == null) throw new java.io.IOException("stream ended without result");
                com.google.gson.Gson gson = new com.google.gson.Gson();
                ImportDtos.ImportResultDto data = gson.fromJson(
                        donePayload.toString(), ImportDtos.ImportResultDto.class);
                List<Track> tracks = new ArrayList<>();
                if (data.results != null) {
                    for (SongDtos.SongDto song : data.results) {
                        if (song.id != null) tracks.add(Track.fromSong(song));
                    }
                }
                List<String> unmatched = data.unmatched != null ? data.unmatched : new ArrayList<>();
                String name = data.playlistName;
                int sourceTotal = data.sourceTotal;
                AppExecutors.onMain(() -> {
                    if (sourceTotal > 0) callback.onSourceTotal(sourceTotal);
                    callback.onSuccess(name, tracks, unmatched);
                });
            } catch (Exception e) {
                // Fallback to single-shot import so older servers still work.
                // Single-shot directly: routing through importFromUrl would
                // loop back into streaming for progress callbacks.
                AppExecutors.onMain(() -> importFromUrlSingleShot(url, limit, callback));
            }
        });
    }

    // ---- Tracks ----

    /** Callback for async track loads; invoked on the main thread. */
    public interface TracksCallback {
        void onTracks(List<Track> tracks);
    }

    /** Async check for device-only (never-synced) playlists; main thread. */
    public interface BooleanCallback {
        void onResult(boolean value);
    }

    /** True when this device holds local-only playlists (guest-created etc.). */
    public void hasLocalPlaylists(BooleanCallback callback) {
        AppExecutors.get().io().execute(() -> {
            boolean found = false;
            for (PlaylistEntity p : db.playlistDao().getPlaylists()) {
                if (p.synced == 0) {
                    found = true;
                    break;
                }
            }
            boolean result = found;
            AppExecutors.onMain(() -> callback.onResult(result));
        });
    }

    /**
     * Room forbids main-thread DB access, so Play buttons must load tracks
     * through this instead of the blocking {@link #getTracks(String)}.
     */
    public void getTracksAsync(String playlistId, TracksCallback callback) {
        AppExecutors.get().io().execute(() -> {
            List<Track> tracks = getTracks(playlistId);
            AppExecutors.onMain(() -> callback.onTracks(tracks));
        });
    }

    public List<Track> getTracks(String playlistId) {
        List<PlaylistTrackEntity> entities = db.playlistDao().getTracks(playlistId);
        List<Track> tracks = new ArrayList<>();
        for (PlaylistTrackEntity e : entities) {
            tracks.add(new Track(e.trackId, e.name, e.artists, e.album, e.imageUrl, null, e.duration, e.year));
        }
        return tracks;
    }

    public LiveData<List<Track>> observeTracks(String playlistId) {
        LiveData<List<PlaylistTrackEntity>> source = db.playlistDao().observeTracks(playlistId);
        return Transformations.map(source, entities -> {
            List<Track> tracks = new ArrayList<>();
            for (PlaylistTrackEntity e : entities) {
                tracks.add(new Track(e.trackId, e.name, e.artists, e.album, e.imageUrl, null, e.duration, e.year));
            }
            return tracks;
        });
    }

    public void addTrack(String playlistId, Track track, ActionCallback callback) {
        AppExecutors.get().io().execute(() -> {
            List<PlaylistTrackEntity> existing = db.playlistDao().getTracks(playlistId);
            if (existing.isEmpty()) {
                db.playlistDao().insertTrack(PlaylistTrackEntity.from(
                        playlistId, track.id, track.name, track.artists, track.album,
                        track.imageUrl, track.durationMs, track.year, 0));
            } else {
                boolean found = false;
                for (PlaylistTrackEntity e : existing) {
                    if (e.trackId.equals(track.id)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    db.playlistDao().insertTrack(PlaylistTrackEntity.from(
                            playlistId, track.id, track.name, track.artists, track.album,
                            track.imageUrl, track.durationMs, track.year, existing.size()));
                }
            }
            PlaylistEntity playlist = db.playlistDao().getPlaylist(playlistId);
            if (playlist != null) {
                playlist.trackCount = db.playlistDao().getTracks(playlistId).size();
                playlist.updatedAt = System.currentTimeMillis();
                db.playlistDao().updatePlaylist(playlist);
            }
            AppExecutors.onMain(() -> callback.onDone(true));
        });
        if (TokenStore.hasToken() && !playlistId.startsWith("local-")) {
            api.addPlaylistTrack(playlistId, new Requests.AddTrackRequest(track.toSnapshot()))
                    .enqueue(new Callback<ApiResponse<UserDtos.PlaylistTracksDto>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c,
                                               Response<ApiResponse<UserDtos.PlaylistTracksDto>> r) {
                            applyServerTracks(playlistId, r);
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c, Throwable t) {
                        }
                    });
        }
    }

    public void removeTrack(String playlistId, String trackId, ActionCallback callback) {
        AppExecutors.get().io().execute(() -> {
            db.playlistDao().deleteTrack(playlistId, trackId);
            // Renumber positions.
            List<PlaylistTrackEntity> rest = db.playlistDao().getTracks(playlistId);
            for (int i = 0; i < rest.size(); i++) {
                db.playlistDao().setPosition(playlistId, rest.get(i).trackId, i);
            }
            PlaylistEntity playlist = db.playlistDao().getPlaylist(playlistId);
            if (playlist != null) {
                playlist.trackCount = rest.size();
                playlist.updatedAt = System.currentTimeMillis();
                db.playlistDao().updatePlaylist(playlist);
            }
            AppExecutors.onMain(() -> callback.onDone(true));
        });
        if (TokenStore.hasToken() && !playlistId.startsWith("local-")) {
            api.removePlaylistTrack(playlistId, trackId)
                    .enqueue(new Callback<ApiResponse<UserDtos.PlaylistTracksDto>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c,
                                               Response<ApiResponse<UserDtos.PlaylistTracksDto>> r) {
                            applyServerTracks(playlistId, r);
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c, Throwable t) {
                        }
                    });
        }
    }

    private void applyServerTracks(String playlistId, Response<ApiResponse<UserDtos.PlaylistTracksDto>> r) {
        ApiResponse<UserDtos.PlaylistTracksDto> body = r.body();
        if (!r.isSuccessful() || body == null || !body.success || body.data == null
                || body.data.tracks == null) {
            return;
        }
        AppExecutors.get().io().execute(() -> {
            List<PlaylistTrackEntity> current = db.playlistDao().getTracks(playlistId);
            Map<String, PlaylistTrackEntity> currentById = new HashMap<>();
            for (PlaylistTrackEntity e : current) {
                currentById.put(e.trackId, e);
            }
            // Add server tracks we don't have locally.
            for (UserDtos.PlaylistTrackDto server : body.data.tracks) {
                if (server.track == null || server.track.id == null || currentById.containsKey(server.track.id)) {
                    continue;
                }
                db.playlistDao().insertTrack(PlaylistTrackEntity.from(
                        playlistId, server.track.id, server.track.name, server.track.artists,
                        server.track.album, server.track.imageUrl,
                        server.track.duration != null ? server.track.duration : 0L,
                        server.track.year != null ? server.track.year : "", server.position));
            }
        });
    }

    /**
     * Creates a playlist, fills it locally, then mirrors it (with its tracks)
     * to the server as one unit. Used by playlist imports so the server echo
     * never races the local track inserts.
     */
    public void createLocalWithTracks(String name, List<Track> tracks, ActionCallback callback) {
        final String localId = "local-" + UUID.randomUUID();
        AppExecutors.get().io().execute(() -> {
            db.playlistDao().insertPlaylist(PlaylistEntity.local(localId, name, System.currentTimeMillis()));
            addTracksInOrder(localId, tracks);
            PlaylistEntity local = db.playlistDao().getPlaylist(localId);
            boolean ok = local != null;
            if (ok && TokenStore.hasToken()) {
                pushLocalPlaylist(local);
            }
            AppExecutors.onMain(() -> callback.onDone(ok));
        });
    }

    /** Inserts many tracks (import results) in one io pass. */
    public void addTracksInOrder(String playlistId, List<Track> tracks) {
        if (playlistId == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        AppExecutors.get().io().execute(() -> {
            java.util.Set<String> have = new java.util.HashSet<>();
            int pos = 0;
            for (PlaylistTrackEntity e : db.playlistDao().getTracks(playlistId)) {
                have.add(e.trackId);
                pos++;
            }
            for (Track t : tracks) {
                if (t == null || t.id == null || have.contains(t.id)) {
                    continue;
                }
                have.add(t.id);
                db.playlistDao().insertTrack(PlaylistTrackEntity.from(
                        playlistId, t.id, t.name, t.artists, t.album,
                        t.imageUrl, t.durationMs, t.year, pos++));
            }
            PlaylistEntity pl = db.playlistDao().getPlaylist(playlistId);
            if (pl != null) {
                pl.trackCount = db.playlistDao().getTracks(playlistId).size();
                pl.updatedAt = System.currentTimeMillis();
                db.playlistDao().updatePlaylist(pl);
            }
        });
    }

    /** Pushes local-only playlists/tracks to the server (called after auth). */
    public void syncFromServer() {
        if (!TokenStore.hasToken()) {
            return;
        }
        api.getPlaylists().enqueue(new Callback<ApiResponse<UserDtos.PlaylistListDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.PlaylistListDto>> c,
                                   Response<ApiResponse<UserDtos.PlaylistListDto>> r) {
                ApiResponse<UserDtos.PlaylistListDto> body = r.body();
                if (!r.isSuccessful() || body == null || !body.success || body.data == null
                        || body.data.items == null) {
                    return;
                }
                List<UserDtos.PlaylistDto> server = body.data.items;
                AppExecutors.get().io().execute(() -> {
                    // Add server playlists we don't know locally.
                    for (UserDtos.PlaylistDto dto : server) {
                        if (db.playlistDao().getPlaylist(dto.id) == null) {
                            db.playlistDao().insertPlaylist(PlaylistEntity.synced(
                                    dto.id, dto.name, dto.description, dto.artworkUrl,
                                    dto.trackCount, parseTime(dto.createdAt), parseTime(dto.updatedAt)));
                        }
                    }
                    // Push local-only playlists (offline-created), unless the
                    // user explicitly declined the guest-conversion offer.
                    if (!com.nightlight.app.util.AccountPrefs.skipLocalPlaylistPush(app)) {
                        for (PlaylistEntity local : db.playlistDao().getPlaylists()) {
                            if (local.synced == 0) {
                                pushLocalPlaylist(local);
                            }
                        }
                    }
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.PlaylistListDto>> c, Throwable t) {
            }
        });
    }

    private void pushLocalPlaylist(PlaylistEntity local) {
        api.createPlaylist(new Requests.PlaylistCreateRequest(local.name))
                .enqueue(new Callback<ApiResponse<UserDtos.PlaylistDetailDto>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c,
                                           Response<ApiResponse<UserDtos.PlaylistDetailDto>> r) {
                        ApiResponse<UserDtos.PlaylistDetailDto> body = r.body();
                        if (!r.isSuccessful() || body == null || !body.success || body.data == null
                                || body.data.playlist == null) {
                            return;
                        }
                        String serverId = body.data.playlist.id;
                        // Room must not be touched on the main thread (Retrofit
                        // callbacks land here): read + rewrite on the io pool.
                        AppExecutors.get().io().execute(() -> {
                            List<PlaylistTrackEntity> tracks = db.playlistDao().getTracks(local.id);
                            for (PlaylistTrackEntity t : tracks) {
                                db.playlistDao().insertTrack(PlaylistTrackEntity.from(
                                        serverId, t.trackId, t.name, t.artists, t.album,
                                        t.imageUrl, t.duration, t.year, t.position));
                                Track track = new Track(t.trackId, t.name, t.artists, t.album,
                                        t.imageUrl, null, t.duration, t.year);
                                api.addPlaylistTrack(serverId, new Requests.AddTrackRequest(track.toSnapshot()))
                                        .enqueue(new Callback<ApiResponse<UserDtos.PlaylistTracksDto>>() {
                                            @Override
                                            public void onResponse(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c,
                                                                   Response<ApiResponse<UserDtos.PlaylistTracksDto>> r) {
                                            }

                                            @Override
                                            public void onFailure(Call<ApiResponse<UserDtos.PlaylistTracksDto>> c, Throwable t) {
                                            }
                                        });
                            }
                            db.playlistDao().clearTracks(local.id);
                            db.playlistDao().deletePlaylist(local);
                            db.playlistDao().insertPlaylist(PlaylistEntity.synced(
                                    serverId, local.name, local.description, local.artworkUrl,
                                    tracks.size(), local.createdAt, System.currentTimeMillis()));
                        });
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<UserDtos.PlaylistDetailDto>> c, Throwable t) {
                    }
                });
    }

    private static long parseTime(String iso) {
        if (iso == null) {
            return System.currentTimeMillis();
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
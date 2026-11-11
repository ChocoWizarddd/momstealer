package com.nightlight.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.nightlight.app.data.db.entity.PlaylistEntity;
import com.nightlight.app.data.db.entity.PlaylistTrackEntity;

import java.util.List;

@Dao
public interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    LiveData<List<PlaylistEntity>> observePlaylists();

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    List<PlaylistEntity> getPlaylists();

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    PlaylistEntity getPlaylist(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPlaylist(PlaylistEntity entity);

    @Update
    void updatePlaylist(PlaylistEntity entity);

    @Delete
    void deletePlaylist(PlaylistEntity entity);

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    List<PlaylistTrackEntity> getTracks(String playlistId);

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    LiveData<List<PlaylistTrackEntity>> observeTracks(String playlistId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTrack(PlaylistTrackEntity entity);

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    void deleteTrack(String playlistId, String trackId);

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    void clearTracks(String playlistId);

    @Query("DELETE FROM playlist_tracks")
    void clearAllTracks();

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    void setPosition(String playlistId, String trackId, int position);
}
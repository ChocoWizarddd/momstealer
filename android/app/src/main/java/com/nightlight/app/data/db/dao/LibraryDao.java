package com.nightlight.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.nightlight.app.data.db.entity.LikedTrackEntity;
import com.nightlight.app.data.db.entity.PreferenceEntity;
import com.nightlight.app.data.db.entity.RecentTrackEntity;
import com.nightlight.app.data.db.entity.SearchHistoryEntity;

import java.util.List;

@Dao
public interface LibraryDao {

    // Likes
    @Query("SELECT * FROM liked_tracks ORDER BY createdAt DESC")
    LiveData<List<LikedTrackEntity>> observeLikes();

    @Query("SELECT * FROM liked_tracks ORDER BY createdAt DESC LIMIT :limit")
    List<LikedTrackEntity> getLikes(int limit);

    @Query("SELECT * FROM liked_tracks WHERE trackId = :trackId LIMIT 1")
    LikedTrackEntity getLike(String trackId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertLike(LikedTrackEntity entity);

    @Query("DELETE FROM liked_tracks WHERE trackId = :trackId")
    void deleteLikeById(String trackId);

    @Query("DELETE FROM liked_tracks")
    void clearLikes();

    // Recently played
    @Query("SELECT * FROM recent_tracks ORDER BY createdAt DESC LIMIT :limit")
    LiveData<List<RecentTrackEntity>> observeRecent(int limit);

    @Query("SELECT * FROM recent_tracks ORDER BY createdAt DESC")
    List<RecentTrackEntity> getAllRecent();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertRecent(RecentTrackEntity entity);

    @Query("DELETE FROM recent_tracks WHERE trackId = :trackId")
    void deleteRecentById(String trackId);

    @Query("DELETE FROM recent_tracks")
    void clearRecent();

    // Search history
    @Query("SELECT * FROM search_history ORDER BY createdAt DESC LIMIT :limit")
    LiveData<List<SearchHistoryEntity>> observeHistory(int limit);

    @Query("SELECT * FROM search_history ORDER BY createdAt DESC LIMIT :limit")
    List<SearchHistoryEntity> getHistory(int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertHistory(SearchHistoryEntity entity);

    @Delete
    void deleteHistory(SearchHistoryEntity entity);

    @Query("DELETE FROM search_history WHERE id = :id")
    void deleteHistoryById(long id);

    @Query("DELETE FROM search_history")
    void clearHistory();

    // Preferences
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putPreference(PreferenceEntity entity);

    @Query("SELECT value FROM preferences WHERE key = :key LIMIT 1")
    String getPreference(String key);
}
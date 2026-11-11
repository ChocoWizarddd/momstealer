package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "liked_tracks", indices = {@Index(value = "trackId", unique = true)})
public class LikedTrackEntity {

    @PrimaryKey
    @NonNull
    public String trackId = "";
    public String name;
    public String artists;
    public String album;
    public String imageUrl;
    /** Playable stream URL at like time; null when unknown (resolved on play). */
    public String streamUrl;
    public long duration;
    public String year;
    public long createdAt;

    public static LikedTrackEntity from(String trackId, String name, String artists, String album,
                                        String imageUrl, String streamUrl, long duration,
                                        String year, long createdAt) {
        LikedTrackEntity e = new LikedTrackEntity();
        e.trackId = trackId;
        e.name = name;
        e.artists = artists;
        e.album = album;
        e.imageUrl = imageUrl;
        e.streamUrl = streamUrl;
        e.duration = duration;
        e.year = year;
        e.createdAt = createdAt;
        return e;
    }
}
package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "recent_tracks", indices = {@Index(value = "trackId", unique = true), @Index("createdAt")})
public class RecentTrackEntity {

    @PrimaryKey
    @NonNull
    public String trackId = "";
    public String name;
    public String artists;
    public String album;
    public String imageUrl;
    /** Playable stream URL at record time; null when unknown (resolved on play). */
    public String streamUrl;
    public long duration;
    public String year;
    public long createdAt;

    public static RecentTrackEntity from(LikedTrackEntity like) {
        RecentTrackEntity e = new RecentTrackEntity();
        e.trackId = like.trackId;
        e.name = like.name;
        e.artists = like.artists;
        e.album = like.album;
        e.imageUrl = like.imageUrl;
        e.streamUrl = like.streamUrl;
        e.duration = like.duration;
        e.year = like.year;
        e.createdAt = System.currentTimeMillis();
        return e;
    }
}
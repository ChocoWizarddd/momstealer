package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "playlists", indices = {@Index("createdAt")})
public class PlaylistEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    @NonNull
    public String name = "";
    public String description;
    public String artworkUrl;
    public int trackCount;
    public long createdAt;
    public long updatedAt;

    /** 1 when the server has acknowledged this playlist, 0 for offline-created. */
    public int synced;

    public static PlaylistEntity local(String id, String name, long now) {
        PlaylistEntity e = new PlaylistEntity();
        e.id = id;
        e.name = name;
        e.description = "";
        e.artworkUrl = "";
        e.trackCount = 0;
        e.createdAt = now;
        e.updatedAt = now;
        e.synced = 0;
        return e;
    }

    public static PlaylistEntity synced(String id, String name, String description, String artworkUrl,
                                        int trackCount, long createdAt, long updatedAt) {
        PlaylistEntity e = new PlaylistEntity();
        e.id = id;
        e.name = name;
        e.description = description;
        e.artworkUrl = artworkUrl;
        e.trackCount = trackCount;
        e.createdAt = createdAt;
        e.updatedAt = updatedAt;
        e.synced = 1;
        return e;
    }
}
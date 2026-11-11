package com.nightlight.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "playlist_tracks",
        indices = {@Index(value = {"playlistId", "trackId"}, unique = true), @Index("position")},
        primaryKeys = {"playlistId", "trackId"})
public class PlaylistTrackEntity {

    @NonNull
    public String playlistId = "";

    @NonNull
    public String trackId = "";

    public String name;
    public String artists;
    public String album;
    public String imageUrl;
    public long duration;
    public String year;
    public int position;

    public static PlaylistTrackEntity from(String playlistId, String trackId, String name, String artists,
                                           String album, String imageUrl, long duration, String year, int position) {
        PlaylistTrackEntity e = new PlaylistTrackEntity();
        e.playlistId = playlistId;
        e.trackId = trackId;
        e.name = name;
        e.artists = artists;
        e.album = album;
        e.imageUrl = imageUrl;
        e.duration = duration;
        e.year = year;
        e.position = position;
        return e;
    }
}
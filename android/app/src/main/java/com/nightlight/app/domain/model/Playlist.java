package com.nightlight.app.domain.model;

import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.data.db.entity.PlaylistEntity;

/** Immutable playlist summary. */
public final class Playlist {

    public final String id;
    public final String name;
    public final String description;
    public final String artworkUrl;
    public final int trackCount;
    public final boolean synced;

    public Playlist(String id, String name, String description, String artworkUrl, int trackCount, boolean synced) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.artworkUrl = artworkUrl;
        this.trackCount = trackCount;
        this.synced = synced;
    }

    public static Playlist fromDto(UserDtos.PlaylistDto dto) {
        return new Playlist(
                dto.id,
                dto.name != null ? dto.name : "Untitled",
                dto.description != null ? dto.description : "",
                dto.artworkUrl != null ? dto.artworkUrl : "",
                dto.trackCount,
                true);
    }

    public static Playlist fromEntity(PlaylistEntity e) {
        return new Playlist(e.id, e.name, e.description, e.artworkUrl, e.trackCount, e.synced != 0);
    }
}
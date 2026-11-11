package com.nightlight.app.data.api.dto;

import java.util.List;

public final class SongDtos {
    private SongDtos() {
    }

    public static final class DownloadLinkDto {
        public String quality;
        public String url;
    }

    public static final class ArtistDto {
        public String id;
        public String name;
        public String role;
        public String type;
        public List<DownloadLinkDto> image;
        public String url;
    }

    public static final class AlbumDto {
        public String id;
        public String name;
        public String url;
    }

    /** Full song document returned by /api/search/songs and /api/songs. */
    public static final class SongDto {
        public String id;
        public String name;
        public String type;
        public String year;
        public String releaseDate;
        public Integer duration; // seconds
        public String label;
        public boolean explicitContent;
        public Integer playCount;
        public String language;
        public boolean hasLyrics;
        public String lyricsId;
        public String url;
        public String copyright;
        public AlbumDto album;
        public ArtistsDto artists;
        public List<DownloadLinkDto> image;
        public List<DownloadLinkDto> downloadUrl;
    }

    public static final class ArtistsDto {
        public List<ArtistDto> primary;
        public List<ArtistDto> featured;
        public List<ArtistDto> all;
    }

    public static final class SearchSongsDto {
        public int total;
        public int start;
        public List<SongDto> results;
    }
}
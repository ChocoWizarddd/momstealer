package com.nightlight.app.data.api.dto;

import java.util.List;

public final class TrendingDtos {
    private TrendingDtos() {
    }

    /** One item from /api/search/trending. */
    public static final class TrendingDto {
        public List<SongDtos.SongDto> songs;
        public List<AlbumItemDto> albums;
        public List<ChartDto> charts;
        public String chartTitle;
        public long fetchedAt;
    }

    public static final class AlbumItemDto {
        public String id;
        public String title;
        public String subtitle;
        public String image;
        public String url;
        public String language;
        public String year;
    }

    public static final class ChartDto {
        public String id;
        public String title;
        public String image;
        public String url;
    }
}
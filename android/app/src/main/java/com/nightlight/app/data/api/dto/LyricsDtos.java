package com.nightlight.app.data.api.dto;

import java.util.List;

/** Response of GET /api/lyrics (synchronized lyrics). */
public final class LyricsDtos {
    private LyricsDtos() {
    }

    public static final class LineDto {
        public Integer timeMs; // null when the lyrics carry no timestamps
        public String text;
    }

    public static final class LyricsDto {
        public boolean available;
        public boolean instrumental;
        public boolean timed;
        public List<LineDto> lines;
    }
}

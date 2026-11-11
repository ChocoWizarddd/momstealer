package com.nightlight.app.data.api.dto;

import java.util.List;

/** Response of POST /api/import/playlist (Spotify/YouTube conversion). */
public final class ImportDtos {
    private ImportDtos() {
    }

    public static final class ImportResultDto {
        public String source;
        public String playlistName;
        public int totalTracks;
        public int sourceTotal;
        public int matched;
        public List<String> unmatched;
        public List<SongDtos.SongDto> results;
    }
}

package com.nightlight.app.data.api.dto;

import java.util.List;

public final class UserDtos {
    private UserDtos() {
    }

    public static final class AuthDataDto {
        public String token;
        public UserDto user;
    }

    public static final class UserDto {
        public String id;
        public String deviceId;
        public String createdAt;
    }

    /**
     * Identity profile. The backend endpoint is {@code GET auth/me}, which today
     * serves only {@code id}, {@code stats} and {@code preferences} — the
     * identity fields below stay null on that response. Permanent username and
     * photo are sourced from the Firebase exchange response instead (see
     * {@code AuthRepository}); every consumer must therefore treat these fields
     * as optional and keep the locally cached value when they are absent.
     */
    public static final class ProfileDto {
        public String id;
        public String email;
        public String username;
        public String displayName;
        public String photoUrl;
        public String createdAt;
        public StatsDto stats;
        public Object preferences;

        /** Aggregate counts served alongside the profile. */
        public static final class StatsDto {
            public int likedTracks;
            public int playlists;
        }
    }

    /** Minimal track snapshot stored server-side. */
    public static final class TrackSnapshotDto {
        public String id;
        public String name;
        public String artists;
        public String album;
        public String imageUrl;
        public Integer duration; // ms
        public String year;
    }

    public static final class LikeDto {
        public String trackId;
        public TrackSnapshotDto track;
        public String createdAt;
    }

    public static final class LikesDto {
        public List<LikeDto> items;
        public int total;
        public int page;
        public int limit;
    }

    public static final class LikedIdsDto {
        public List<String> ids;
    }

    public static final class RecentDto {
        public String trackId;
        public TrackSnapshotDto track;
        public String playedAt;
    }

    public static final class RecentListDto {
        public List<RecentDto> items;
    }

    public static final class HistoryDto {
        public String id;
        public String query;
        public String createdAt;
    }

    public static final class HistoryListDto {
        public List<HistoryDto> items;
    }

    public static final class PlaylistDto {
        public String id;
        public String name;
        public String description;
        public String artworkUrl;
        public int trackCount;
        public String createdAt;
        public String updatedAt;
    }

    public static final class PlaylistListDto {
        public List<PlaylistDto> items;
    }

    public static final class PlaylistTrackDto {
        public String id;
        public String trackId;
        public TrackSnapshotDto track;
        public int position;
        public String addedAt;
    }

    public static final class PlaylistDetailDto {
        public PlaylistDto playlist;
        public List<PlaylistTrackDto> tracks;
    }

    public static final class PlaylistTracksDto {
        public List<PlaylistTrackDto> tracks;
    }

    public static final class PrefsDto {
        public Integer repeatMode;
        public Boolean shuffle;
    }

    public static final class PrefsResponseDto {
        public PrefsDto preferences;
    }
}
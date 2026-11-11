package com.nightlight.app.data.api.dto;

public final class Requests {
    private Requests() {
    }

    public static final class RegisterRequest {
        public String deviceId;
        public String platform = "android";
        public String appVersion;

        public RegisterRequest(String deviceId, String appVersion) {
            this.deviceId = deviceId;
            this.appVersion = appVersion;
        }
    }

    public static final class LikeRequest {
        public UserDtos.TrackSnapshotDto track;

        public LikeRequest(UserDtos.TrackSnapshotDto track) {
            this.track = track;
        }
    }

    public static final class SearchHistoryRequest {
        public String query;

        public SearchHistoryRequest(String query) {
            this.query = query;
        }
    }

    public static final class PlaylistCreateRequest {
        public String name;
        public String description = "";
        public String artworkUrl = "";

        public PlaylistCreateRequest(String name) {
            this.name = name;
        }
    }

    public static final class PlaylistUpdateRequest {
        public String name;
        public String description;

        public PlaylistUpdateRequest(String name) {
            this.name = name;
        }
    }

    public static final class AddTrackRequest {
        public UserDtos.TrackSnapshotDto track;
        public Integer position;

        public AddTrackRequest(UserDtos.TrackSnapshotDto track) {
            this.track = track;
        }
    }

    public static final class ImportRequest {
        public String url;
        public int limit;

        public ImportRequest(String url, int limit) {
            this.url = url;
            this.limit = limit;
        }
    }

    /**
     * Threshold-crossed play event. Sent once per track when playback passes
     * min(30s, 50% of duration) — screen opens and bare starts never count.
     */
    public static final class PlayEventRequest {
        public String trackId;
        public String title;
        public java.util.List<String> artists;
        public long positionMs;
        public long durationMs;

        public PlayEventRequest(String trackId, String title, java.util.List<String> artists,
                                long positionMs, long durationMs) {
            this.trackId = trackId;
            this.title = title;
            this.artists = artists;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }
}
package com.nightlight.app.data.api.dto;

/** Listen-together session DTOs (see backend /api/sessions). */
public final class SessionsDtos {

    private SessionsDtos() {
    }

    /** Minimal track metadata shared between listeners (URLs are re-resolved). */
    public static class TrackSnapshot {
        public String id;
        public String name;
        public String artists;
        public String album;
        public String imageUrl;
        public long duration;
        public String year;
    }

    public static class CreateRequest {
        public String deviceId;
        public String name;
        public TrackSnapshot track;
    }

    public static class JoinRequest {
        public String code;
        public String deviceId;
        public String name;
    }

    public static class UpdateStateRequest {
        public String deviceId;
        public TrackSnapshot track;
        public Long positionMs;
        public Boolean playing;
    }

    public static class SessionState {
        public TrackSnapshot track;
        public long positionMs;
        public boolean playing;
        public long updatedAt;
    }

    public static class SessionDto {
        public String code;
        public String owner;
        public int members;
        public SessionState state;
    }

    // Chat DTOs — wire field for message content is `text` on both directions.

    public static class ChatSendRequest {
        public String deviceId;
        public String name;
        public String text;

        public ChatSendRequest(String deviceId, String name, String text) {
            this.deviceId = deviceId;
            this.name = name;
            this.text = text;
        }
    }

    public static class ChatMessage {
        public String id;
        public String deviceId;
        public String name;
        public String text;
        public long createdAt;
    }

    public static class ChatMessagesResponse extends java.util.ArrayList<ChatMessage> {
    }
}

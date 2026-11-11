package com.nightlight.app.data.api.dto;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** Payloads for GET /api/reco/smartqueue (multi-source candidates + evidence). */
public final class RecoDtos {

    public static final class EvidenceDto {
        @SerializedName("id")
        public String id;
        @SerializedName("title")
        public String title;
        @SerializedName("artist")
        public String artist;
        @SerializedName("sources")
        public List<String> sources = new ArrayList<>();
        @SerializedName("score")
        public double score;
        @SerializedName("reason")
        public String reason;
    }

    public static final class SmartQueueDto {
        /** Playable provider-shaped song rows (same shape as search/radio). */
        @SerializedName("tracks")
        public List<SongDtos.SongDto> tracks = new ArrayList<>();
        @SerializedName("evidence")
        public List<EvidenceDto> evidence = new ArrayList<>();
    }
}

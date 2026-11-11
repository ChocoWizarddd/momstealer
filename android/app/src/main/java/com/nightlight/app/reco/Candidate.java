package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One recommendation candidate: a track plus the set of generator sources
 * that surfaced it. Multi-sourced candidates (e.g. RADIO_SEED + LIKED
 * neighborhood) carry independent evidence and score higher. The human-
 * readable {@link #reason} is recorded for physical-validation logging
 * (§32: title / artist / genre / source / reason per track).
 */
public final class Candidate {

    /** Candidate generator sources (§4 A–I). */
    public enum Source {
        RADIO_SEED,        // A: current-track radio / related songs (provider)
        RELATED_ARTIST,    // B: related artists
        SAME_GENRE,        // C: same genre slice
        RELATED_GENRE,     // D: related-genre slice
        LIKED_NEIGHBORHOOD,// E: liked-track neighborhood
        FAVORITE_ARTIST,   // F: user favorite-artist neighborhood
        RECENT_SUCCESS,    // G: recent successful listening neighborhood
        TRENDING,          // H: popular/trending (light signal only)
        EXPLORATION,       // I: controlled exploration (still relevant)
        CARRIED_QUEUE,     // already-queued carryover (toggle/augment path)
        EXTERNAL_SIMILAR   // backend similarity (Last.fm/ReccoBeats re-resolved)
    }

    public final Track track;
    public final Set<Source> sources;
    public final String reason;

    public Candidate(Track track, Source source, String reason) {
        this.track = track;
        this.sources = EnumSet.of(source);
        this.reason = reason != null ? reason : "";
    }

    public void addSource(Source source, String extraReason) {
        if (source != null) sources.add(source);
    }

    public Set<Source> sources() {
        return Collections.unmodifiableSet(sources);
    }
}

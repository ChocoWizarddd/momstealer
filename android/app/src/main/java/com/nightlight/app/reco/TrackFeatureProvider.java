package com.nightlight.app.reco;

import com.nightlight.app.domain.model.Track;

import java.util.HashMap;
import java.util.Map;

/**
 * Audio-feature source contract (§10). Implementations MUST be synchronous
 * and fast (cached/preloaded) — never network-on-call, never on the playback
 * path. Unknown tracks return {@link TrackFeatures#unknown()}.
 *
 * Current implementations:
 * - {@link JioSaavnFeatureProvider}: provider exposes no audio features →
 *   always unknown (honest, not zero-filled).
 * - {@link MapBackedFeatureProvider}: cache decorator for backend-supplied
 *   snapshots (ReccoBeats audio-features via /api/reco, once ID mapping
 *   exists). Falls through to the delegate on miss.
 */
public interface TrackFeatureProvider {

    TrackFeatures featuresFor(Track track);

    /** JioSaavn exposes no audio features: always unknown. */
    final class JioSaavnFeatureProvider implements TrackFeatureProvider {
        private static final JioSaavnFeatureProvider INSTANCE = new JioSaavnFeatureProvider();

        public static JioSaavnFeatureProvider get() {
            return INSTANCE;
        }

        @Override
        public TrackFeatures featuresFor(Track track) {
            return TrackFeatures.unknown();
        }
    }

    /** Cache decorator: backend-supplied snapshots, delegate on miss. */
    final class MapBackedFeatureProvider implements TrackFeatureProvider {
        private final TrackFeatureProvider delegate;
        private final Map<String, TrackFeatures> cache = new HashMap<>();

        public MapBackedFeatureProvider(TrackFeatureProvider delegate) {
            this.delegate = delegate != null ? delegate
                    : JioSaavnFeatureProvider.get();
        }

        public synchronized void put(String trackId, TrackFeatures features) {
            if (trackId == null || features == null) return;
            if (cache.size() > 2000) cache.clear();
            cache.put(trackId, features);
        }

        @Override
        public synchronized TrackFeatures featuresFor(Track track) {
            if (track != null && track.id != null) {
                TrackFeatures hit = cache.get(track.id);
                if (hit != null) return hit;
            }
            return delegate.featuresFor(track);
        }
    }
}

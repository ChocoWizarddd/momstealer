package com.nightlight.app.playback;

/**
 * Where playback came from. Every playback action resolves to exactly one of
 * these, and the type decides what the queue is allowed to contain.
 *
 * <p>No type is special-cased in the queue: they all flow through the same
 * context → order → session → window pipeline.
 */
public enum PlaybackContextType {

    PLAYLIST("playlist"),
    ALBUM("album"),
    ARTIST("artist"),
    SEARCH("search"),
    LIKED("liked"),
    LIBRARY("library"),
    RECOMMENDATION("recommendation"),
    SINGLE("single"),
    AUTOPLAY("autoplay");

    private final String wire;

    PlaybackContextType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * True when the context is a real collection that can be exhausted and then
     * handed over to Autoplay. {@link #SINGLE} is deliberately not one: an
     * isolated track has nothing to continue into, so Autoplay takes over
     * immediately.
     */
    public boolean isCollection() {
        return this != SINGLE;
    }

    /** True when Autoplay takes over the moment this context runs out. */
    public boolean handsOverToAutoplay() {
        return this != AUTOPLAY;
    }
}
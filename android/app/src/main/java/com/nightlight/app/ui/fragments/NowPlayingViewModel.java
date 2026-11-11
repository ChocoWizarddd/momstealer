package com.nightlight.app.ui.fragments;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exposes the single authoritative playback state as LiveData plus the liked
 * state of the current track. Survives configuration changes.
 */
public class NowPlayingViewModel extends AndroidViewModel {

    private final PlaybackManager playback;
    private final LibraryRepository library;
    private final MutableLiveData<PlaybackSnapshot> snapshot = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> likedIds = new MutableLiveData<>(new HashSet<>());
    private final Observer<List<Track>> likesObserver = this::onLikesChanged;

    private final PlaybackManager.Listener playbackListener = snapshot::setValue;

    public NowPlayingViewModel(@NonNull Application application) {
        super(application);
        NightLightApp app = (NightLightApp) application;
        this.playback = PlaybackManager.get(app);
        this.library = app.getLibraryRepository();
        library.observeLikes().observeForever(likesObserver);
    }

    @Override
    protected void onCleared() {
        library.observeLikes().removeObserver(likesObserver);
        super.onCleared();
    }

    public void onStart() {
        playback.addListener(playbackListener);
    }

    public void onStop() {
        playback.removeListener(playbackListener);
    }

    public LiveData<PlaybackSnapshot> getSnapshot() {
        return snapshot;
    }

    public LiveData<Set<String>> getLikedIds() {
        return likedIds;
    }

    public boolean isLiked(String trackId) {
        Set<String> set = likedIds.getValue();
        return set != null && trackId != null && set.contains(trackId);
    }

    public void toggleLike(Track track) {
        boolean like = !isLiked(track.id);
        library.toggleLike(track, like, null);
        if (like) {
            likedIds.getValue().add(track.id);
        } else {
            likedIds.getValue().remove(track.id);
        }
        likedIds.setValue(new HashSet<>(likedIds.getValue()));
    }

    public void onLikesChanged(List<Track> tracks) {
        Set<String> ids = new HashSet<>();
        for (Track t : tracks) {
            ids.add(t.id);
        }
        likedIds.setValue(ids);
    }

    // Playback actions delegate straight to the single source of truth.

    public void togglePlayPause() {
        playback.togglePlayPause();
    }

    public void next() {
        playback.next();
    }

    public void previous() {
        playback.previous();
    }

    public void seekTo(long positionMs) {
        playback.seekTo(positionMs);
    }

    public int cycleRepeat() {
        return playback.cycleRepeat();
    }

    /** @return the newly active shuffle mode: smart | normal | off */
    public String toggleShuffle() {
        return playback.toggleShuffle();
    }
}
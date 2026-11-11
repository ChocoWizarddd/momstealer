package com.nightlight.app.ui.fragments;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryViewModel extends AndroidViewModel {

    private final LibraryRepository library;
    private final PlaylistRepository playlists;
    private final Set<String> likedIds = new HashSet<>();
    private final Observer<List<Track>> likesObserver = this::onLikesChanged;
    private LiveData<List<Track>> likesLiveData;

    public LibraryViewModel(@NonNull Application application) {
        super(application);
        NightLightApp app = (NightLightApp) application;
        this.library = app.getLibraryRepository();
        this.playlists = app.getPlaylistRepository();
        likesLiveData = library.observeLikes();
        likesLiveData.observeForever(likesObserver);
    }

    @Override
    protected void onCleared() {
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
        super.onCleared();
    }

    public LiveData<List<Track>> getLikes() {
        return library.observeLikes();
    }

    public LiveData<List<Playlist>> getPlaylists() {
        return playlists.observePlaylists();
    }

    private void onLikesChanged(List<Track> tracks) {
        Set<String> ids = new HashSet<>();
        if (tracks != null) {
            for (Track t : tracks) {
                ids.add(t.id);
            }
        }
        likedIds.clear();
        likedIds.addAll(ids);
    }

    public void toggleLike(Track track) {
        // Like state is read from the observed set; a direct Room
        // read here would crash (main-thread database access).
        boolean like = !likedIds.contains(track.id);
        if (like) {
            likedIds.add(track.id);
        } else {
            likedIds.remove(track.id);
        }
        library.toggleLike(track, like, null);
    }

    public void createPlaylist(String name) {
        playlists.createPlaylist(name, ok -> {
        });
    }

    public void deletePlaylist(Playlist playlist) {
        playlists.deletePlaylist(playlist.id, ok -> {
        });
    }

    public void renamePlaylist(Playlist playlist, String name) {
        playlists.renamePlaylist(playlist.id, name);
    }
}
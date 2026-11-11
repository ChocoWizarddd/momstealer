package com.nightlight.app.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.ui.PlaylistActivity;
import com.nightlight.app.ui.adapters.PlaylistAdapter;
import com.nightlight.app.ui.adapters.TrackAdapter;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class LibraryFragment extends Fragment {

    private NightLightApp app;
    private LibraryRepository library;
    private PlaylistRepository playlists;

    private TrackAdapter likesAdapter;
    private PlaylistAdapter playlistAdapter;
    private TextView likedCount;
    private View likesEmpty;
    private View playlistsEmpty;
    private View likesHeader;
    private View likesContainer;
    private View playlistsHeader;
    private View playlistsContainer;
    private TextView filterAll;
    private TextView filterPlaylists;
    private TextView filterLiked;
    private TextView sortButton;
    private List<Playlist> currentPlaylists = new ArrayList<>();
    private int libraryFilter;
    private boolean alphabetical;

    private LiveData<List<Track>> likesLiveData;
    private LiveData<List<Playlist>> playlistsLiveData;

    private final Observer<List<Track>> likesObserver = tracks -> {
        likedCount.setText(tracks == null ? "" : String.valueOf(tracks.size()));
        likesEmpty.setVisibility(tracks == null || tracks.isEmpty() ? View.VISIBLE : View.GONE);
        Set<String> ids = new HashSet<>();
        if (tracks != null) {
            for (Track t : tracks) {
                ids.add(t.id);
            }
        }
        likesAdapter.setLikedIds(ids);
        likesAdapter.submitList(tracks);
    };

    private final Observer<List<Playlist>> playlistsObserver = items -> {
        currentPlaylists = items == null ? new ArrayList<>() : new ArrayList<>(items);
        playlistsEmpty.setVisibility(items == null || items.isEmpty() ? View.VISIBLE : View.GONE);
        renderPlaylistOrder();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = (NightLightApp) requireActivity().getApplication();
        library = app.getLibraryRepository();
        playlists = app.getPlaylistRepository();

        likedCount = view.findViewById(R.id.library_liked_count);
        likesEmpty = view.findViewById(R.id.library_likes_empty);
        playlistsEmpty = view.findViewById(R.id.library_playlists_empty);
        likesHeader = view.findViewById(R.id.library_liked_row);
        likesContainer = view.findViewById(R.id.library_likes_container);
        playlistsHeader = view.findViewById(R.id.library_playlists_header);
        playlistsContainer = view.findViewById(R.id.library_playlists_container);
        filterAll = view.findViewById(R.id.library_filter_all);
        filterPlaylists = view.findViewById(R.id.library_filter_playlists);
        filterLiked = view.findViewById(R.id.library_filter_liked);
        sortButton = view.findViewById(R.id.library_sort);
        android.widget.ImageView avatar = view.findViewById(R.id.library_avatar);
        String avatarUrl = com.nightlight.app.util.AccountPrefs.photoUrl(requireContext());
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(avatarUrl).circleCrop()
                    .placeholder(R.drawable.nightlight_logo)
                    .error(R.drawable.nightlight_logo).into(avatar);
        } else {
            avatar.setImageResource(R.drawable.nightlight_logo);
        }
        avatar.setOnClickListener(v -> startActivity(
                new android.content.Intent(requireContext(), com.nightlight.app.ui.AccountActivity.class)));
        filterAll.setOnClickListener(v -> setLibraryFilter(0));
        filterPlaylists.setOnClickListener(v -> setLibraryFilter(1));
        filterLiked.setOnClickListener(v -> setLibraryFilter(2));
        sortButton.setOnClickListener(v -> {
            alphabetical = !alphabetical;
            renderPlaylistOrder();
        });
        setLibraryFilter(0);

        RecyclerView likesList = view.findViewById(R.id.library_likes);
        likesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        likesList.setNestedScrollingEnabled(false);
        likesAdapter = new TrackAdapter(new TrackAdapter.Callbacks() {
            @Override
            public void onTrackClick(Track track) {
                TrackPlayer.play(requireContext(), likesAdapter.getCurrentList(),
                        indexOf(likesAdapter.getCurrentList(), track));
            }

            @Override
            public void onLikeClick(Track track) {
                library.toggleLike(track, false, null);
            }

            @Override
            public void onMoreClick(Track track) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(track.name)
                        .setItems(new CharSequence[]{
                                requireContext().getString(R.string.action_add_to_playlist),
                                "Play next"
                        }, (dialog, which) -> {
                            if (which == 0) {
                                PlaylistDialogs.showAddToPlaylistSheet(requireActivity(), track);
                            } else {
                                com.nightlight.app.player.PlaybackManager.get(requireContext()).playNext(track);
                            }
                        })
                        .show();
            }
        });
        likesList.setAdapter(likesAdapter);

        RecyclerView playlistList = view.findViewById(R.id.library_playlists);
        playlistList.setLayoutManager(new LinearLayoutManager(requireContext()));
        playlistList.setNestedScrollingEnabled(false);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Callbacks() {
            @Override
            public void onPlaylistClick(Playlist playlist) {
                startActivity(PlaylistActivity.intent(requireContext(), playlist.id, playlist.name));
            }

            @Override
            public void onMoreClick(Playlist playlist) {
                showPlaylistMenu(playlist);
            }
        });
        playlistList.setAdapter(playlistAdapter);

        view.findViewById(R.id.library_new_playlist).setOnClickListener(v -> createPlaylist());

        view.findViewById(R.id.library_import).setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(
                    requireContext(), v);
            menu.getMenu().add(0, 1, 0, R.string.action_new_playlist);
            menu.getMenu().add(0, 2, 1, R.string.cd_import);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    createPlaylist();
                    return true;
                }
                if (item.getItemId() == 2) {
                    importPlaylist();
                    return true;
                }
                return false;
            });
            menu.show();
        });

        // Explicit guest labeling: local playlists are temporary, saving to
        // the account requires sign-in (spec: never a silent failure).
        // NOTE: the fragment root is a ScrollView which can host only ONE
        // direct child, so the banner is added INSIDE the content column.
        if (com.nightlight.app.util.AccountPrefs.isGuest(requireContext())) {
            TextView guestBanner = new TextView(requireContext());
            guestBanner.setText(R.string.guest_banner);
            guestBanner.setTextSize(12f);
            guestBanner.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nightlight_gold));
            guestBanner.setPadding(0, dp(4), 0, dp(4));
            guestBanner.setClickable(true);
            guestBanner.setFocusable(true);
            guestBanner.setOnClickListener(v ->
                    com.nightlight.app.ui.common.PlaylistDialogs.showGuestAccountPrompt(requireActivity(), () -> {
                    }));
            android.view.ViewGroup contentColumn =
                    (android.view.ViewGroup) view.findViewById(R.id.library_liked_row).getParent();
            if (contentColumn != null) {
                contentColumn.addView(guestBanner, Math.min(1, contentColumn.getChildCount()));
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setLibraryFilter(int mode) {
        libraryFilter = mode;
        boolean showLikes = mode != 1;
        boolean showPlaylists = mode != 2;
        likesHeader.setVisibility(showLikes ? View.VISIBLE : View.GONE);
        likesContainer.setVisibility(showLikes ? View.VISIBLE : View.GONE);
        playlistsHeader.setVisibility(showPlaylists ? View.VISIBLE : View.GONE);
        playlistsContainer.setVisibility(showPlaylists ? View.VISIBLE : View.GONE);
        setFilterStyle(filterAll, mode == 0);
        setFilterStyle(filterPlaylists, mode == 1);
        setFilterStyle(filterLiked, mode == 2);
    }

    private void setFilterStyle(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_library_filter_selected : R.drawable.bg_library_filter);
        chip.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                selected ? R.color.nightlight_ivory : R.color.nightlight_cream_dim));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void renderPlaylistOrder() {
        if (sortButton == null || playlistAdapter == null) return;
        sortButton.setText(alphabetical ? "A–Z" : "Recently added");
        sortButton.setContentDescription(alphabetical
                ? "Sort playlists by recently added"
                : "Sort playlists alphabetically");
        List<Playlist> ordered = new ArrayList<>(currentPlaylists);
        if (alphabetical) {
            ordered.sort(Comparator.comparing(p -> p.name == null ? "" : p.name,
                    String.CASE_INSENSITIVE_ORDER));
        }
        playlistAdapter.submitList(ordered);
    }

    /** Import from Spotify, Apple Music, or YouTube with explicit source selection. */
    private void showImportDialog() {
        // Shared import dialog: source picker (Spotify / Apple Music /
        // YouTube) + URL entry, with honest imported-vs-parsed counts.
        PlaylistDialogs.showImportDialog(requireActivity());
    }

    private void createPlaylist() {
        PlaylistDialogs.showCreateDialog(requireContext(),
                getString(R.string.playlist_create_title), name ->
                        playlists.createPlaylist(name, ok -> { }));
    }

    private void importPlaylist() {
        if (com.nightlight.app.util.AccountPrefs.isGuest(requireContext())) {
            PlaylistDialogs.showGuestAccountPrompt(requireActivity(), () -> { });
            return;
        }
        showImportDialog();
    }

    private void showPlaylistMenu(Playlist playlist) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(playlist.name)
                .setItems(new CharSequence[]{
                        getString(R.string.action_play_playlist),
                        getString(R.string.playlist_rename_title),
                        getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) {
                        playPlaylist(playlist);
                    } else if (which == 1) {
                        PlaylistDialogs.showCreateDialog(requireContext(),
                                getString(R.string.playlist_rename_title),
                                name -> playlists.renamePlaylist(playlist.id, name));
                    } else if (which == 2) {
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
                                .setPositiveButton(R.string.action_delete, (d, w) ->
                                        playlists.deletePlaylist(playlist.id, ok -> {
                                        }))
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                })
                .show();
    }

    private void playPlaylist(Playlist playlist) {
        playlists.getTracksAsync(playlist.id, tracks -> {
            if (tracks.isEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                        R.string.empty_playlists, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            TrackPlayer.play(requireContext(), tracks, 0);
        });
    }

    private static int indexOf(List<Track> tracks, Track track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id.equals(track.id)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void onStart() {
        super.onStart();
        likesLiveData = library.observeLikes();
        likesLiveData.observe(this, likesObserver);
        playlistsLiveData = playlists.observePlaylists();
        playlistsLiveData.observe(this, playlistsObserver);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
        if (playlistsLiveData != null) {
            playlistsLiveData.removeObserver(playlistsObserver);
        }
    }
}

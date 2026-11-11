package com.nightlight.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
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
import com.nightlight.app.ui.adapters.TrackAdapter;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlaylistActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "playlist_id";
    private static final String EXTRA_NAME = "playlist_name";

    private String playlistId;
    private PlaylistRepository playlists;
    private LibraryRepository library;
    private TrackAdapter adapter;
    private TextView countText;
    private TextView titleText;
    private ImageView artworkView;

    private LiveData<List<Track>> tracksLiveData;
    private LiveData<List<Track>> likesLiveData;

    private final Observer<List<Track>> tracksObserver = tracks -> {
        countText.setText(getString(R.string.playlist_tracks_count, tracks == null ? 0 : tracks.size()));
        adapter.submitList(tracks);
        if (tracks == null || tracks.isEmpty() || tracks.get(0).imageUrl == null
                || tracks.get(0).imageUrl.isEmpty()) {
            artworkView.setImageResource(R.drawable.bg_artwork_placeholder);
        } else {
            Glide.with(this).load(tracks.get(0).imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .centerCrop().into(artworkView);
        }
    };

    private final Set<String> likedIds = new HashSet<>();

    private final Observer<List<Track>> likesObserver = likes -> {
        Set<String> ids = new HashSet<>();
        for (Track t : likes) {
            ids.add(t.id);
        }
        likedIds.clear();
        likedIds.addAll(ids);
        adapter.setLikedIds(ids);
    };

    public static Intent intent(Context context, String playlistId, String name) {
        return new Intent(context, PlaylistActivity.class)
                .putExtra(EXTRA_ID, playlistId)
                .putExtra(EXTRA_NAME, name);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_playlist);
        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.activity_playlist_root));

        playlistId = getIntent().getStringExtra(EXTRA_ID);
        if (playlistId == null) {
            finish();
            return;
        }
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) {
            name = "Playlist";
        }

        NightLightApp app = (NightLightApp) getApplication();
        playlists = app.getPlaylistRepository();
        library = app.getLibraryRepository();

        titleText = findViewById(R.id.playlist_title);
        titleText.setText(name);
        countText = findViewById(R.id.playlist_count);
        artworkView = findViewById(R.id.playlist_artwork);

        findViewById(R.id.playlist_back).setOnClickListener(v -> finish());
        findViewById(R.id.playlist_play).setOnClickListener(v ->
                playlists.getTracksAsync(playlistId, tracks -> {
                    if (tracks.isEmpty()) {
                        Toast.makeText(this, R.string.empty_playlists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    TrackPlayer.play(this, tracks, 0);
                }));
        findViewById(R.id.playlist_more).setOnClickListener(v -> showPlaylistMenu());

        RecyclerView list = findViewById(R.id.playlist_tracks);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrackAdapter(new TrackAdapter.Callbacks() {
            @Override
            public void onTrackClick(Track track) {
                TrackPlayer.play(PlaylistActivity.this, adapter.getCurrentList(),
                        indexOf(adapter.getCurrentList(), track));
            }

            @Override
            public void onLikeClick(Track track) {
                // Read the like state from the observed set; a direct
                // Room read here would crash (main-thread database access).
                boolean like = !likedIds.contains(track.id);
                if (like) {
                    likedIds.add(track.id);
                } else {
                    likedIds.remove(track.id);
                }
                library.toggleLike(track, like, null);
            }

            @Override
            public void onMoreClick(Track track) {
                new androidx.appcompat.app.AlertDialog.Builder(PlaylistActivity.this)
                        .setTitle(track.name)
                        .setItems(new CharSequence[]{
                                getString(R.string.action_add_to_playlist),
                                "Remove from this playlist"
                        }, (dialog, which) -> {
                            if (which == 0) {
                                PlaylistDialogs.showAddToPlaylistSheet(PlaylistActivity.this, track);
                            } else {
                                playlists.removeTrack(playlistId, track.id, ok -> {
                                });
                            }
                        })
                        .show();
            }
        });
        list.setAdapter(adapter);
    }

    private void showPlaylistMenu() {
        String[] options = {
                getString(R.string.playlist_rename_title),
                getString(R.string.action_delete)
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titleText.getText())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        PlaylistDialogs.showCreateDialog(this,
                                getString(R.string.playlist_rename_title), name -> {
                                    playlists.renamePlaylist(playlistId, name);
                                    titleText.setText(name);
                                });
                    } else {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setMessage(getString(R.string.playlist_delete_confirm, titleText.getText()))
                                .setPositiveButton(R.string.action_delete, (d, w) -> {
                                    playlists.deletePlaylist(playlistId, ok -> finish());
                                })
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                })
                .show();
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
    protected void onStart() {
        super.onStart();
        tracksLiveData = playlists.observeTracks(playlistId);
        tracksLiveData.observe(this, tracksObserver);
        likesLiveData = library.observeLikes();
        likesLiveData.observe(this, likesObserver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tracksLiveData != null) {
            tracksLiveData.removeObserver(tracksObserver);
        }
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
    }
}

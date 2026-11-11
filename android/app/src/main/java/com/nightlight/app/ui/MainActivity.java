package com.nightlight.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.bumptech.glide.Glide;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.ui.fragments.HomeFragment;
import com.nightlight.app.ui.fragments.LibraryFragment;
import com.nightlight.app.ui.fragments.SearchFragment;
import com.nightlight.app.util.NetworkMonitor;

import androidx.activity.OnBackPressedCallback;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Application shell: hosts the three primary fragments, the persistent mini
 * player and the offline banner. Playback lives in the media service; this
 * activity merely observes it.
 */
public final class MainActivity extends AppCompatActivity {

    private HomeFragment homeFragment;
    private SearchFragment searchFragment;
    private LibraryFragment libraryFragment;
    private Fragment current;

    private View miniPlayer;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlayPause;
    private ImageButton miniLike;
    private View miniProgressFill;
    private TextView offlineBanner;
    private ImageView tabletArtwork;
    private TextView tabletTitle;
    private TextView tabletArtist;
    private TextView tabletAlbum;

    private final PlaybackManager.Listener playbackListener = this::onPlaybackChanged;
    private final Observer<Boolean> onlineObserver = this::onConnectivityChanged;
    private LiveData<Boolean> onlineLiveData;

    private final Observer<List<Track>> likesObserver = this::onLikesChanged;
    private LiveData<List<Track>> likesLiveData;
    private final Set<String> miniLikedIds = new HashSet<>();

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        NavigationBarView nav = findViewById(R.id.bottom_nav);

        // Fragment content must start below the status bar (edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragment_container), (v, insets) -> {
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0);
            return insets;
        });

        miniPlayer = findViewById(R.id.mini_player);
        miniArtwork = findViewById(R.id.mini_artwork);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);
        miniLike = findViewById(R.id.mini_like);
        miniProgressFill = findViewById(R.id.mini_progress_fill);
        offlineBanner = findViewById(R.id.offline_banner);
        tabletArtwork = findViewById(R.id.tablet_now_playing_artwork);
        tabletTitle = findViewById(R.id.tablet_now_playing_title);
        tabletArtist = findViewById(R.id.tablet_now_playing_artist);
        tabletAlbum = findViewById(R.id.tablet_now_playing_album);
        View tabletPanel = findViewById(R.id.tablet_now_playing_panel);
        if (tabletPanel != null) {
            tabletPanel.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
            ViewCompat.setOnApplyWindowInsetsListener(tabletPanel, (v, insets) -> {
                v.setPadding(v.getPaddingLeft(),
                        Math.max(v.getPaddingTop(), insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + 12),
                        v.getPaddingRight(),
                        Math.max(v.getPaddingBottom(), insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom));
                return insets;
            });
        }

        // Navigation bar inset for the bottom nav (edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> {
            if (v instanceof NavigationRailView) {
                v.setPadding(insets.getInsets(WindowInsetsCompat.Type.systemBars()).left,
                        insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0,
                        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
            } else {
                v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
            }
            return insets;
        });

        // Mini player: whole row opens Now Playing; play/pause toggles.
        miniPlayer.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
        miniPlayPause.setOnClickListener(v -> PlaybackManager.get(this).togglePlayPause());
        miniLike.setOnClickListener(v -> {
            PlaybackSnapshot s = PlaybackManager.get(this).getSnapshot();
            if (s == null || s.current == null) {
                return;
            }
            NightLightApp app = (NightLightApp) getApplication();
            com.nightlight.app.data.repo.LibraryRepository library = app.getLibraryRepository();
            // Like state is read from the observed set — a direct
            // Room read here would crash (main-thread database access).
            boolean like = !miniLikedIds.contains(s.current.id);
            if (like) {
                miniLikedIds.add(s.current.id);
            } else {
                miniLikedIds.remove(s.current.id);
            }
            library.toggleLike(s.current, like, null);
            renderMiniLike(like);
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    // Single batch result (Map<String, Boolean>).
                    // Gracefully handle denial: app continues without notifications.
                    // No-op here; playback and UI work without the permission.
                });
        maybeRequestPermissions();

        setupFragments(nav);
        setupOfflineBanner();
        setupBackNavigation();
    }

    private void setupFragments(NavigationBarView nav) {
        homeFragment = new HomeFragment();
        searchFragment = new SearchFragment();
        libraryFragment = new LibraryFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, libraryFragment, "library")
                .hide(libraryFragment)
                .add(R.id.fragment_container, searchFragment, "search")
                .hide(searchFragment)
                .add(R.id.fragment_container, homeFragment, "home")
                .commit();
        current = homeFragment;
        nav.setSelectedItemId(R.id.nav_home);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                show(homeFragment);
            } else if (id == R.id.nav_search) {
                show(searchFragment);
                searchFragment.onSearchTabTapped();
            } else if (id == R.id.nav_library) {
                show(libraryFragment);
            }
            return true;
        });
    }

    private void show(Fragment fragment) {
        if (fragment == current) {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                .hide(current)
                .show(fragment)
                .commit();
        current = fragment;
    }

    private void setupOfflineBanner() {
        onlineLiveData = NetworkMonitor.get(this).online();
        onlineLiveData.observe(this, onlineObserver);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (current != homeFragment) {
                    // If on Search or Library, return to Home.
                    ((NavigationBarView) findViewById(R.id.bottom_nav))
                            .setSelectedItemId(R.id.nav_home);
                } else {
                    // On Home: show exit confirmation.
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Leave NightLight?")
                            .setMessage("Do you really want to quit the app?")
                            .setPositiveButton("Quit", (d, w) -> finish())
                            .setNegativeButton("Stay", null)
                            .show();
                }
            }
        });
    }

    private void onConnectivityChanged(Boolean online) {
        offlineBanner.setVisibility(Boolean.TRUE.equals(online) ? View.GONE : View.VISIBLE);
    }

    private static final String PREFS_PERMS = "nightlight_perms";
    private static final String KEY_NOTIF_ASKED = "notif_asked";

    private void maybeRequestPermissions() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // Don't nag on every launch: if permanently denied ("Don't ask again"),
        // shouldShowRequestPermissionRationale is false and we've already asked once.
        boolean alreadyAsked = getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
                .getBoolean(KEY_NOTIF_ASKED, false);
        boolean shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);

        if (alreadyAsked && !shouldShowRationale) {
            // User denied with "Don't ask again" or system policy — respect it.
            return;
        }
        if (shouldShowRationale) {
            // Contextual explanation before re-asking (spec: don't silently re-prompt).
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Stay in the groove")
                    .setMessage("Allow notifications so playback controls stay in your notification shade while music plays. You can change this anytime in system settings.")
                    .setPositiveButton("Allow", (d, w) -> {
                        getSharedPreferences(PREFS_PERMS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
                        permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
                    })
                    .setNegativeButton("Not now", (d, w) -> {
                        getSharedPreferences(PREFS_PERMS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
                    })
                    .show();
            return;
        }
        // First-time ask: batch all genuinely required runtime permissions at once.
        getSharedPreferences(PREFS_PERMS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
        permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
    }

    /** Public entry for fragments to switch tabs. */
    public void switchToSearch() {
        ((NavigationBarView) findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_search);
    }

    public void switchToLibrary() {
        ((NavigationBarView) findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_library);
    }

    private String miniLoadedId;

    /** Heart reflects the persisted like state; magenta when liked. */
    private void renderMiniLike(boolean liked) {
        miniLike.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        miniLike.setColorFilter(androidx.core.content.ContextCompat.getColor(this,
                liked ? R.color.nightlight_magenta : R.color.nightlight_cream_dim));
    }

    private void onLikesChanged(List<Track> likes) {
        Set<String> ids = new HashSet<>();
        if (likes != null) {
            for (Track t : likes) {
                ids.add(t.id);
            }
        }
        miniLikedIds.clear();
        miniLikedIds.addAll(ids);
        if (miniLoadedId != null) {
            renderMiniLike(miniLikedIds.contains(miniLoadedId));
        }
    }

    private void onPlaybackChanged(PlaybackSnapshot snapshot) {
        if (snapshot == null) return;
        boolean visible = snapshot.hasQueue && snapshot.current != null;
        miniPlayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            if (tabletTitle != null) {
                tabletTitle.setText("Nothing playing");
                tabletArtist.setText("Choose a track to start listening");
                tabletAlbum.setText("");
                tabletArtwork.setImageResource(R.drawable.bg_artwork_placeholder);
            }
            return;
        }
        Track track = snapshot.current;
        boolean trackChanged = !track.id.equals(miniLoadedId);
        miniLoadedId = track.id;
        miniTitle.setText(track.name);
        miniArtist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);
        if (tabletTitle != null) {
            tabletTitle.setText(track.name);
            tabletArtist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);
            tabletAlbum.setText(track.album == null || track.album.trim().isEmpty() ? "" : "Album · " + track.album);
            if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
                Glide.with(this).load(track.imageUrl)
                        .placeholder(R.drawable.bg_artwork_placeholder)
                        .error(R.drawable.bg_artwork_placeholder)
                        .centerCrop().into(tabletArtwork);
            } else {
                tabletArtwork.setImageResource(R.drawable.bg_artwork_placeholder);
            }
        }
        miniPlayPause.setImageResource(snapshot.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        long progressDuration = snapshot.duration > 0 ? snapshot.duration : track.durationMs;
        float progressFraction = progressDuration > 0
                ? Math.max(0f, Math.min(1f, (float) snapshot.position / progressDuration)) : 0f;
        miniPlayer.post(() -> {
            if (miniPlayer.getWidth() <= 0 || miniProgressFill == null) return;
            android.view.ViewGroup.LayoutParams params = miniProgressFill.getLayoutParams();
            params.width = Math.round(miniPlayer.getWidth() * progressFraction);
            miniProgressFill.setLayoutParams(params);
        });
        // Like state comes from the observed set (Room must never be
        // queried on the main thread — playback callbacks run there).
        renderMiniLike(miniLikedIds.contains(track.id));
        if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
            Glide.with(this)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(120, 120)
                    .centerCrop()
                    .into(miniArtwork);
        }
        if (trackChanged) {
            // Track-change crossfade (spec 32): artwork + text fade in instead
            // of an instant swap.
            miniArtwork.setAlpha(0f);
            miniArtwork.animate().alpha(1f).setDuration(260).start();
            miniTitle.setAlpha(0.3f);
            miniTitle.animate().alpha(1f).setDuration(300).start();
        }
    }

    // Intentionally no super call: back either returns to Home or asks for
    // exit confirmation — never the default finish().
    @SuppressWarnings("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (current != homeFragment) {
            ((NavigationBarView) findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_home);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Leave NightLight?")
                .setMessage("Do you really want to quit the app? Your music will stop.")
                .setPositiveButton("Quit", (dialog, which) -> finishAndRemoveTask())
                .setNegativeButton("Stay", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager.get(this).addListener(playbackListener);
        likesLiveData = ((NightLightApp) getApplication()).getLibraryRepository().observeLikes();
        likesLiveData.observe(this, likesObserver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        PlaybackManager.get(this).removeListener(playbackListener);
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
    }

    @Override
    protected void onDestroy() {
        if (onlineLiveData != null) {
            onlineLiveData.removeObserver(onlineObserver);
        }
        super.onDestroy();
    }
}

package com.nightlight.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.ui.adapters.QueueAdapter;
import com.nightlight.app.ui.fragments.NowPlayingViewModel;

import java.util.List;
import java.util.Set;

public final class NowPlayingActivity extends AppCompatActivity {

    private static final int DISC_PX_DEFAULT = 900;
    private static final float BACKDROP_ALPHA_BALANCED = 0.52f;
    /**
     * Fraction of the stage's shorter edge the record occupies. Slightly under
     * full bleed so a sliver of atmosphere always frames the disc instead of
     * letting it touch the stage edges.
     */
    private static final float DISC_STAGE_FRACTION = 0.94f;
    /** Disc diameter never exceeds this many pixels, to bound the bitmap cost. */
    private static final int DISC_PX_MAX = 1080;

    private NowPlayingViewModel viewModel;

    private ImageView backdrop;
    private TextView title;
    private TextView artist;
    private com.nightlight.app.ui.common.WaveformSeekView seekBar;
    private TextView positionText;
    private TextView durationText;
    private ImageButton playPause;
    private ImageButton shuffle;
    private ImageButton repeat;
    private ImageButton likeIcon;
    private View artworkStage;
    private com.nightlight.app.ui.common.VinylView discView;
    private View banner;

    private boolean discSized;
    private int discPx = DISC_PX_DEFAULT;
    private String loadedArtworkId;
    private com.nightlight.app.util.AmbientAnimator ambient;
    private String ambientKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_now_playing);

        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.now_playing_root));

        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(NowPlayingViewModel.class);

        backdrop = findViewById(R.id.np_backdrop);
        artworkStage = findViewById(R.id.np_artwork_stage);
        discView = findViewById(R.id.np_disc_view);
        banner = findViewById(R.id.np_banner);
        artworkStage.post(this::ensureDiscSize);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        // Marquee only scrolls while the TextView is selected, and this screen
        // never takes focus, so opt the banner's two lines in explicitly.
        title.setSelected(true);
        artist.setSelected(true);
        seekBar = findViewById(R.id.np_seek);
        positionText = findViewById(R.id.np_position);
        durationText = findViewById(R.id.np_duration);
        playPause = findViewById(R.id.np_play_pause);
        shuffle = findViewById(R.id.np_shuffle);
        repeat = findViewById(R.id.np_repeat);
        likeIcon = findViewById(R.id.np_like);
        View lyricsBox = findViewById(R.id.np_lyrics);

        findViewById(R.id.np_back).setOnClickListener(v -> finish());
        lyricsBox.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LyricsActivity.class)));
        findViewById(R.id.np_share).setOnClickListener(v -> showMoreMenu());
        playPause.setOnClickListener(v -> {
            viewModel.togglePlayPause();
            playPause.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
                    .withEndAction(() -> playPause.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                    .start();
        });
        findViewById(R.id.np_next).setOnClickListener(v -> viewModel.next());
        findViewById(R.id.np_previous).setOnClickListener(v -> viewModel.previous());
        shuffle.setOnClickListener(v -> {
            String mode = viewModel.toggleShuffle();
            String label = "smart".equals(mode) ? getString(R.string.shuffle_smart_label)
                    : "normal".equals(mode) ? getString(R.string.shuffle_normal_label)
                    : getString(R.string.shuffle_off_label);
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            renderShuffleMode(mode);
        });
        repeat.setOnClickListener(v -> {
            int mode = viewModel.cycleRepeat();
            ((NightLightApp) getApplication()).getLibraryRepository().setRepeatPref(mode);
            renderRepeat(mode);
        });
        findViewById(R.id.np_like).setOnClickListener(v -> {
            PlaybackSnapshot s = viewModel.getSnapshot().getValue();
            if (s != null && s.current != null) {
                viewModel.toggleLike(s.current);
            }
        });
        findViewById(R.id.np_queue).setOnClickListener(v -> showQueueSheet());

        seekBar.setSeekListener(fraction -> {
            PlaybackSnapshot snap = viewModel.getSnapshot().getValue();
            long duration = snap != null ? snap.duration : 0L;
            long pos = (long) (duration * fraction);
            positionText.setText(Track.formatDuration(pos));
            viewModel.seekTo(pos);
        });
    }

    private final androidx.lifecycle.Observer<PlaybackSnapshot> snapshotObserver = this::render;
    private final androidx.lifecycle.Observer<Set<String>> likesObserver = this::renderLikes;

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.onStart();
        viewModel.getSnapshot().observe(this, snapshotObserver);
        viewModel.getLikedIds().observe(this, likesObserver);
        // Shuffle state always re-syncs from the persisted preference when the
        // screen becomes visible — no stale icon from a previous session.
        renderShuffleMode(com.nightlight.app.util.ShufflePrefs.mode(this));
        startAmbient();
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.onStop();
        viewModel.getSnapshot().removeObserver(snapshotObserver);
        viewModel.getLikedIds().removeObserver(likesObserver);
        discView.stopSpinning();
        stopAmbient();
    }

    /**
     * Starts the ambient motion system. Rebuilt only when the playing state
     * actually changes (render() runs every tick).
     */
    private void startAmbient() {
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        boolean playing = s != null && s.isPlaying;
        String key = String.valueOf(playing);
        if (key.equals(ambientKey) && ambient != null) {
            return;
        }
        ambientKey = key;
        stopAmbient();
        ambient = com.nightlight.app.util.AmbientAnimator.forNowPlaying(banner, backdrop, null);
        ambient.start();
    }

    private void stopAmbient() {
        if (ambient != null) {
            ambient.stop();
            ambient = null;
        }
    }

    private void render(PlaybackSnapshot s) {
        if (s == null || s.current == null) {
            finish();
            return;
        }
        Track track = s.current;
        title.setText(track.name);
        artist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);

        ensureDiscSize();
        applyPlayingVisuals(s.isPlaying);
        discView.setSpinning(s.isPlaying);
        if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
            if (!track.id.equals(loadedArtworkId)) {
                loadedArtworkId = track.id;
                // Track transition: banner + backdrop fade in, disc label
                // restarts upright. No instant swap, no layout jump.
                discView.setAlpha(0f);
                backdrop.setAlpha(0f);
                title.setAlpha(0f);
                artist.setAlpha(0f);
                title.animate().alpha(1f).setDuration(340).setStartDelay(70).start();
                artist.animate().alpha(1f).setDuration(340).setStartDelay(130).start();
                com.nightlight.app.util.AmbientAnimator.enter(banner);
            }
            loadDiscLabel(track);
            Glide.with(this)
                    .load(track.imageUrl)
                    .override(720, 720)
                    .centerCrop()
                    .into(backdrop);
            discView.animate().alpha(1f).setDuration(400).start();
            backdrop.animate().alpha(1f).setDuration(900).start();
        }

        playPause.setImageResource(s.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        long duration = s.duration > 0 ? s.duration : track.durationMs;
        if (!seekBar.isDragging()) {
            seekBar.setMax(1000);
            seekBar.setProgress(duration > 0 ? (int) (s.position * 1000 / duration) : 0);
            positionText.setText(Track.formatDuration(s.position));
        }
        durationText.setText(Track.formatDuration(duration));

        renderRepeat(s.repeatMode);
        renderShuffleMode(com.nightlight.app.util.ShufflePrefs.mode(this));
        renderLikes(viewModel.getLikedIds().getValue());
    }

    /**
     * Applies the visual profile for the current playback state. Power modes
     * were removed in v1.0.0, so the balanced constants live here directly.
     * Kept as a method because it still re-syncs the ambient layer when the
     * playing state flips.
     */
    private void applyPlayingVisuals(boolean playing) {
        backdrop.setAlpha(BACKDROP_ALPHA_BALANCED);
        startAmbient();
    }

    /**
     * Shuffle states: OFF neutral, NORMAL gold, SMART gold + smart glyph +
     * label badge. Reads the persisted preference; the player contract is
     * applyShuffleMode() → publish() → this render, so UI and Media3 agree.
     */
    private void renderShuffleMode(String mode) {
        boolean smart = "smart".equals(mode);
        boolean normal = "normal".equals(mode);
        shuffle.setImageResource(smart ? R.drawable.ic_shuffle_smart : R.drawable.ic_shuffle);
        shuffle.setColorFilter(ContextCompat.getColor(this,
                (smart || normal) ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
        shuffle.setContentDescription(smart ? getString(R.string.shuffle_smart_label)
                : normal ? getString(R.string.shuffle_normal_label)
                : getString(R.string.shuffle_off_label));
        TextView label = findViewById(R.id.np_shuffle_label);
        if (label != null) {
            label.setText(smart ? "SMART" : normal ? "SHUFFLE" : "OFF");
            label.setTextColor(ContextCompat.getColor(this,
                    (smart || normal) ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
        }
    }

    /**
     * Sizes the record to a square that fits its stage. The disc drawable is a
     * set of concentric ovals, so it must always get a square box or the
     * record renders as an ellipse.
     */
    private void ensureDiscSize() {
        if (discSized || artworkStage == null || artworkStage.getWidth() <= 0) {
            return;
        }
        int side = Math.round(Math.min(artworkStage.getWidth(), artworkStage.getHeight())
                * DISC_STAGE_FRACTION);
        if (side <= 0) {
            return;
        }
        discSized = true;
        discPx = Math.min(side, DISC_PX_MAX);
        android.view.ViewGroup.LayoutParams lp = discView.getLayoutParams();
        lp.width = side;
        lp.height = side;
        discView.setLayoutParams(lp);
    }

    /**
     * Fetches the album art as a bitmap and hands it to the disc as its center
     * label. Glide targets a View by default, so this uses an explicit
     * {@link com.bumptech.glide.request.target.CustomTarget} — the disc paints
     * the label itself and clips it to a circle.
     */
    private void loadDiscLabel(Track track) {
        int size = Math.max(1, discPx);
        Glide.with(this)
                .asBitmap()
                .load(track.imageUrl)
                .override(size, size)
                .centerCrop()
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(android.graphics.Bitmap resource,
                                                com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        discView.setArtwork(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        discView.setArtwork(null);
                    }

                    @Override
                    public void onLoadFailed(@Nullable android.graphics.drawable.Drawable errorDrawable) {
                        // Leave the previous label rather than flashing blank.
                    }
                });
    }

    private void renderRepeat(int mode) {
        repeat.setImageResource(mode == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(ContextCompat.getColor(this,
                mode == 0 ? R.color.nightlight_cream_dim : R.color.nightlight_gold));
    }

    private void renderLikes(Set<String> likedIds) {
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        if (s == null || s.current == null || likedIds == null) {
            return;
        }
        boolean liked = likedIds.contains(s.current.id);
        likeIcon.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        likeIcon.setColorFilter(ContextCompat.getColor(this,
                liked ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
    }

    /** More options: share the listening session, or open the queue. */
    private void showMoreMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, findViewById(R.id.np_share));
        menu.getMenu().add(0, 1, 0, R.string.action_share);
        menu.getMenu().add(0, 2, 0, R.string.action_queue);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                shareListenSession();
                return true;
            }
            if (item.getItemId() == 2) {
                showQueueSheet();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void shareListenSession() {
        if (com.nightlight.app.player.ListenTogether.get().isActive()) {
            com.nightlight.app.player.ListenTogether.shareCode(this,
                    com.nightlight.app.player.ListenTogether.get().activeCode());
            return;
        }
        Toast.makeText(this, R.string.listen_starting, Toast.LENGTH_SHORT).show();
        com.nightlight.app.player.ListenTogether.get().startHosting(this,
                new com.nightlight.app.player.ListenTogether.CodeCallback() {
                    @Override
                    public void onCode(String code) {
                        com.nightlight.app.player.ListenTogether.shareCode(NowPlayingActivity.this, code);
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(NowPlayingActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showQueueSheet() {
        PlaybackManager playback = PlaybackManager.get(this);
        List<Track> queue = playback.getQueueTracks();
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        int currentIndex = s != null ? s.currentIndex : 0;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_to_playlist, null);
        TextView header = view.findViewById(R.id.add_to_playlist_new);
        header.setText(R.string.action_queue);
        header.setOnClickListener(v -> {
            playback.clearQueue();
            sheet.dismiss();
        });
        view.findViewById(R.id.add_to_playlist_list).setBackgroundColor(android.graphics.Color.TRANSPARENT);

        QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Callbacks() {
            @Override
            public void onItemClick(int index) {
                playback.seekToIndex(index);
                sheet.dismiss();
            }

            @Override
            public void onRemoveClick(int index) {
                playback.removeQueueItem(index);
            }
        });
        RecyclerView list = view.findViewById(R.id.add_to_playlist_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        adapter.submit(queue, currentIndex);

        sheet.setContentView(view);
        sheet.show();
    }
}

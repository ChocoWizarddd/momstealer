package com.nightlight.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.api.dto.LyricsDtos;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotify-style synchronized lyrics. Current line is centered in the viewport,
 * highlighted with a soft glow and larger type; other lines stay quiet. If the
 * user scrolls manually, auto-follow pauses until they tap "Resume current
 * line".
 */
public final class LyricsActivity extends Activity implements PlaybackManager.Listener {

    private static final int ACTIVE = 0xFFFFF9EC;
    private static final int DIM = 0x73FFFFFF;
    private static final int MAGENTA_GLOW = 0x66E91E8C;

    private RecyclerView list;
    private TextView state;
    private TextView songLabel;
    private LinearLayout stateBox;
    private ProgressBar progress;
    private TextView resumePill;
    private ImageView backdrop;
    private ImageView art;
    private ImageButton lyricsPlay;

    private LyricsAdapter adapter = new LyricsAdapter();
    private final List<LyricsDtos.LineDto> lines = new ArrayList<>();
    private boolean timed;
    private long durationMs;
    private int activeLine = -1;
    private String lyricsForId;
    private boolean loading;
    private boolean retried;
    private boolean userScrolling;
    private boolean followAnimating;
    private boolean paddingSized;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);
        InsetsUtil.applySystemBars(findViewById(R.id.lyrics_root));

        list = findViewById(R.id.lyrics_list);
        state = findViewById(R.id.lyrics_state);
        stateBox = findViewById(R.id.lyrics_state_box);
        progress = findViewById(R.id.lyrics_progress);
        songLabel = findViewById(R.id.lyrics_song);
        resumePill = findViewById(R.id.lyrics_resume);
        backdrop = findViewById(R.id.lyrics_backdrop);
        art = findViewById(R.id.lyrics_art);
        lyricsPlay = findViewById(R.id.lyrics_play_pause);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.lyrics_back).setOnClickListener(v -> finish());
        resumePill.setOnClickListener(v -> resumeFollowing());
        findViewById(R.id.lyrics_previous).setOnClickListener(v ->
                PlaybackManager.get(this).previous());
        findViewById(R.id.lyrics_next).setOnClickListener(v ->
                PlaybackManager.get(this).next());
        lyricsPlay.setOnClickListener(v -> PlaybackManager.get(this).togglePlayPause());

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    followAnimating = false;
                    return;
                }
                // Only genuine user drags pause the auto-follow; programmatic
                // follow scrolling is guarded by followAnimating.
                if (!followAnimating
                        && (newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_SETTLING)) {
                    userScrolling = true;
                    resumePill.setVisibility(View.VISIBLE);
                }
            }
        });
        list.post(() -> sizePaddings());
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager.get(this).addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        PlaybackManager.get(this).removeListener(this);
        super.onStop();
    }

    @Override
    public void onPlaybackChanged(PlaybackSnapshot snapshot) {
        refresh();
    }

    private void refresh() {
        PlaybackSnapshot snapshot = PlaybackManager.get(this).getSnapshot();
        Track track = snapshot.current;
        if (track == null) {
            return;
        }
        songLabel.setText(track.name);
        lyricsPlay.setImageResource(snapshot.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
            int corner = Math.round(12f * getResources().getDisplayMetrics().density);
            Glide.with(this)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder_round)
                    .error(R.drawable.bg_artwork_placeholder_round)
                    .transform(new com.bumptech.glide.load.resource.bitmap.RoundedCorners(corner))
                    .into(art);
            Glide.with(this).load(track.imageUrl).override(640, 640).centerCrop().into(backdrop);
        }
        durationMs = snapshot.duration;

        if (!track.id.equals(lyricsForId)) {
            lyricsForId = track.id;
            retried = false;
            userScrolling = false;
            resumePill.setVisibility(View.GONE);
            lines.clear();
            timed = false;
            activeLine = -1;
            adapter.notifyDataSetChanged();
            loadLyrics(track);
        }
        updateActiveLine();
    }

    private PlaybackSnapshot snapshot() {
        return PlaybackManager.get(this).getSnapshot();
    }

    private void loadLyrics(Track track) {
        loading = true;
        showState(getString(R.string.lyrics_loading), true);
        fetchLyrics(track, false);
    }

    private void fetchLyrics(Track track, boolean isRetry) {
        loading = true;
        showState(getString(R.string.lyrics_loading), true);
        NightLightApp app = (NightLightApp) getApplication();
        app.getMusicRepository().fetchLyrics(track, new MusicRepository.LyricsCallback() {
            @Override
            public void onSuccess(LyricsDtos.LyricsDto lyrics) {
                loading = false;
                if (!track.id.equals(lyricsForId)) {
                    return; // the song changed while we were fetching
                }
                if (!lyrics.available || lyrics.instrumental) {
                    showState(getString(R.string.lyrics_none), false);
                    return;
                }
                timed = lyrics.timed;
                lines.clear();
                if (lyrics.lines != null) {
                    lines.addAll(lyrics.lines);
                }
                if (lines.isEmpty()) {
                    showState(getString(R.string.lyrics_none), false);
                    return;
                }
                hideState();
                adapter.notifyDataSetChanged();
                updateActiveLine();
            }

            @Override
            public void onFailure(Throwable error) {
                // Cold-start provider slowness is retried once before showing
                // "not available". Playback never waits for lyrics.
                if (!isRetry && track.id.equals(lyricsForId)) {
                    retried = true;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (track.id.equals(lyricsForId)) {
                            fetchLyrics(track, true);
                        }
                    }, 1500);
                    return;
                }
                loading = false;
                if (track.id.equals(lyricsForId)) {
                    showState(getString(R.string.lyrics_none), false);
                }
            }
        });
    }

    private void showState(String message, boolean spinner) {
        state.setText(message);
        stateBox.setVisibility(View.VISIBLE);
        progress.setVisibility(spinner ? View.VISIBLE : View.GONE);
        list.setVisibility(View.INVISIBLE);
    }

    private void hideState() {
        stateBox.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    /** Centers auto-follow inside the padded viewport (content area = 0.5x). */
    private void sizePaddings() {
        if (paddingSized || list.getHeight() <= 0) {
            return;
        }
        paddingSized = true;
        float density = getResources().getDisplayMetrics().density;
        int half = list.getHeight() / 2;
        int top = Math.max(Math.round(96f * density), half - Math.round(120f * density));
        int bottom = half;
        list.setPadding(0, top, 0, bottom);
    }

    private void updateActiveLine() {
        if (lines.isEmpty() || !paddingSized) {
            return;
        }
        long pos = snapshot().position;
        int index;
        if (timed) {
            index = -1;
            for (int i = 0; i < lines.size(); i++) {
                Integer t = lines.get(i).timeMs;
                if (t == null || t > pos) {
                    break;
                }
                index = i;
            }
        } else if (durationMs > 0) {
            index = (int) Math.min(lines.size() - 1, (pos * lines.size()) / durationMs);
        } else {
            return;
        }
        index = Math.max(0, index);
        if (index != activeLine) {
            int old = activeLine;
            activeLine = index;
            adapter.notifyItemChanged(old);
            adapter.notifyItemChanged(activeLine);
            if (!userScrolling && list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                followTo(activeLine);
            }
        }
    }

    private void followTo(int position) {
        followAnimating = true;
        RecyclerView.SmoothScroller scroller = new LinearSmoothScroller(this) {
            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd,
                                        int snapPreference) {
                // Center the target line in the padded viewport.
                return (boxStart + (boxEnd - boxStart) / 2)
                        - (viewStart + (viewEnd - viewStart) / 2);
            }
        };
        scroller.setTargetPosition(position);
        list.getLayoutManager().startSmoothScroll(scroller);
    }

    private void resumeFollowing() {
        userScrolling = false;
        resumePill.setVisibility(View.GONE);
        updateActiveLine();
    }

    private final class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(LyricsActivity.this);
            tv.setTextSize(19);
            tv.setLineSpacing(8f, 1.1f);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setPadding(40, 14, 40, 14);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            LyricsDtos.LineDto line = lines.get(position);
            holder.text.setText(line.text != null ? line.text : "");
            boolean active = position == activeLine;
            holder.text.setTextColor(active ? ACTIVE : DIM);
            holder.text.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            holder.text.setTextSize(active ? 23 : 19);
            holder.text.setShadowLayer(active ? 18f : 0f, 0f, 0f, MAGENTA_GLOW);
            if (active) {
                holder.text.setPivotX(holder.text.getWidth() / 2f);
                holder.text.setPivotY(holder.text.getHeight() / 2f);
                holder.text.setAlpha(0.72f);
                holder.text.setScaleX(0.97f);
                holder.text.animate().alpha(1f).scaleX(1f).setDuration(220).start();
            } else {
                holder.text.setAlpha(1f);
                holder.text.setScaleX(1f);
            }
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull TextView item) {
                super(item);
                text = item;
            }
        }
    }
}

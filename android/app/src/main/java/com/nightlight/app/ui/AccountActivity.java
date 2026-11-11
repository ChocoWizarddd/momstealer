package com.nightlight.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;

import com.bumptech.glide.Glide;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.reco.TasteProfile;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;
import com.nightlight.app.util.AccountPrefs;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TastePrefs;
import com.nightlight.app.util.TokenStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated full-screen account page (§25-27). Member: large circular photo,
 * username, gmail, listening stats (incl. real Total Playtime), created
 * playlists, liked songs, recently played, sign out. Guest: NightLight
 * avatar, Guest label, sign-in entry — never fake identity, never sign-out.
 */
public final class AccountActivity extends AppCompatActivity {

    private LinearLayout content;
    private int dp;
    private LiveData<List<Track>> likedLive;
    private LiveData<List<Track>> recentLive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        dp = Math.round(getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#121212"));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int hPad = Math.round(20f * dp);
        content.setPadding(hPad, Math.round(12f * dp), hPad, Math.round(28f * dp));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        com.nightlight.app.util.InsetsUtil.applySystemBars(scroll);

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Best-effort backend profile refresh (permanent username/photo);
        // silent failures keep the cached local profile.
        try {
            if (TokenStore.hasToken()) {
                ((NightLightApp) getApplication()).getAuthRepository().refreshProfile();
            }
        } catch (Exception ignored) {
        }
        // Refresh stats (playtime grows while listening elsewhere).
        content.removeAllViews();
        render();
    }

    private void render() {
        // Drop previous observers first: onResume re-renders, and stacked
        // observers would multiply Glide/database work per update (jank).
        if (likedLive != null) likedLive.removeObservers(this);
        if (recentLive != null) recentLive.removeObservers(this);
        likedLive = null;
        recentLive = null;
        addTopBar();
        boolean signedIn = TokenStore.hasToken();
        boolean guest = !signedIn && AccountPrefs.isGuest(this);
        if (signedIn) {
            renderMemberHeader();
            renderStats();
            renderPlaylists();
            renderTrackSection(getString(R.string.account_liked_songs), true);
            renderTrackSection(getString(R.string.account_recently_played), false);
            renderSignOut();
        } else if (guest) {
            renderGuestHeader();
        } else {
            renderSignedOutHeader();
        }
    }

    // ---- chrome ----

    private void addTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(getColor(R.color.nightlight_cream));
        int s = Math.round(40f * dp);
        back.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        back.setPadding(Math.round(8f * dp), Math.round(8f * dp),
                Math.round(8f * dp), Math.round(8f * dp));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(R.string.account_title);
        title.setTextSize(18f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(getColor(R.color.nightlight_cream));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(4f * dp));
        title.setLayoutParams(lp);
        bar.addView(title);
        content.addView(bar);
    }

    private void addSectionTitle(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(getColor(R.color.nightlight_gold));
        h.setTextSize(13f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(26f * dp);
        lp.bottomMargin = Math.round(10f * dp);
        h.setLayoutParams(lp);
        content.addView(h);
    }

    private GradientDrawable cardBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6181818"));
        bg.setCornerRadius(Math.round(16f * dp));
        bg.setStroke(Math.round(1f * dp), Color.parseColor("#26FFFFFF"));
        return bg;
    }

    // ---- headers ----

    /** 112dp circular profile block (photo → circleCrop, avatar fallback). */
    private com.google.android.material.imageview.ShapeableImageView bigAvatar() {
        com.google.android.material.imageview.ShapeableImageView avatar =
                new com.google.android.material.imageview.ShapeableImageView(this);
        int size = Math.round(112f * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.round(18f * dp);
        avatar.setLayoutParams(lp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(
                avatar.getShapeAppearanceModel().withCornerSize(size / 2f));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        avatar.setBackground(bg);
        return avatar;
    }

    private void renderMemberHeader() {
        com.google.android.material.imageview.ShapeableImageView avatar = bigAvatar();
        String photo = AccountPrefs.photoUrl(this);
        if (photo != null && !photo.isEmpty()) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.clearColorFilter();
            Glide.with(this).load(photo).circleCrop()
                    .placeholder(R.drawable.nightlight_logo)
                    .error(R.drawable.nightlight_logo)
                    .into(avatar);
        } else {
            avatar.setImageResource(R.drawable.nightlight_logo);
            avatar.setPadding(Math.round(20f * dp), Math.round(20f * dp),
                    Math.round(20f * dp), Math.round(20f * dp));
        }
        avatar.setContentDescription(getString(R.string.account_title));
        content.addView(avatar);

        String name = AccountPrefs.profileName(this);
        TextView nameText = centeredText(
                (name != null && !name.isEmpty()) ? name : getString(R.string.settings_guest_name),
                22f, Typeface.BOLD, getColor(R.color.nightlight_cream), 10);
        content.addView(nameText);

        String email = AccountPrefs.email(this);
        if (email != null && !email.isEmpty()) {
            content.addView(centeredText(email, 14f, Typeface.NORMAL,
                    getColor(R.color.nightlight_cream_dim), 2));
        }
        centeredPill(getString(R.string.settings_pill_member), true);
    }

    private void renderGuestHeader() {
        com.google.android.material.imageview.ShapeableImageView avatar = bigAvatar();
        avatar.setImageResource(R.drawable.nightlight_logo);
        avatar.setPadding(Math.round(20f * dp), Math.round(20f * dp),
                Math.round(20f * dp), Math.round(20f * dp));
        content.addView(avatar);
        content.addView(centeredText(getString(R.string.settings_guest_name),
                22f, Typeface.BOLD, getColor(R.color.nightlight_cream), 10));
        content.addView(centeredText(getString(R.string.settings_signed_out),
                14f, Typeface.NORMAL, getColor(R.color.nightlight_cream_dim), 2));
        centeredPill(getString(R.string.settings_pill_guest), false);
        goldButton(getString(R.string.settings_sign_in), v -> {
            AccountPrefs.setPendingGuestConversion(this, true);
            Intent login = new Intent(this, LoginActivity.class);
            login.putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_CREATE);
            startActivity(login);
        });
    }

    private void renderSignedOutHeader() {
        renderGuestHeader();
    }

    private TextView centeredText(String text, float sp, int style, int color, int topMarginDp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTypeface(Typeface.create("sans-serif-medium", style));
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topMarginDp * dp);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void centeredPill(String text, boolean gold) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(8f * dp);
        row.setLayoutParams(rowLp);
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(11f);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Math.round(14f * dp), Math.round(5f * dp),
                Math.round(14f * dp), Math.round(5f * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Math.round(12f * dp));
        if (gold) {
            pill.setTextColor(getColor(R.color.nightlight_gold));
            bg.setColor(Color.parseColor("#2662D35B"));
            bg.setStroke(Math.round(1f * dp), Color.parseColor("#6662D35B"));
        } else {
            pill.setTextColor(getColor(R.color.nightlight_cream_dim));
            bg.setColor(Color.parseColor("#1AFFFFFF"));
            bg.setStroke(Math.round(1f * dp), Color.parseColor("#33FFFFFF"));
        }
        pill.setBackground(bg);
        row.addView(pill);
        content.addView(row);
    }

    private void goldButton(String text, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(14f * dp);
        row.setLayoutParams(rowLp);
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(15f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{getColor(R.color.nightlight_gold), Color.parseColor("#48A943")});
        bg.setCornerRadius(Math.round(14f * dp));
        btn.setBackground(bg);
        btn.setPadding(Math.round(36f * dp), Math.round(13f * dp),
                Math.round(36f * dp), Math.round(13f * dp));
        btn.setOnClickListener(onClick);
        row.addView(btn);
        content.addView(row);
    }

    // ---- stats ----

    private void renderStats() {
        addSectionTitle(getString(R.string.account_stats));
        TasteProfile taste = new TasteProfile();
        TastePrefs.loadInto(this, TastePrefs.scopeKey(this), taste);
        long totalMs;
        try {
            totalMs = PlaybackManager.get(this).getTotalPlaytimeMs();
        } catch (Exception e) {
            totalMs = taste.totalPlaytimeMs();
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(statCard(getString(R.string.account_stat_playtime),
                formatPlaytime(totalMs), 1f, true));
        row.addView(statCard(getString(R.string.account_stat_liked),
                String.valueOf(taste.likedCount()), 1f, false));
        row.addView(statCard(getString(R.string.account_stat_completed),
                String.valueOf(taste.completedTrackCount()), 1f, false));
        content.addView(row);
    }

    private View statCard(String label, String value, float weight, boolean first) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(cardBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (!first) lp.setMarginStart(Math.round(10f * dp));
        card.setLayoutParams(lp);
        card.setPadding(0, Math.round(14f * dp), 0, Math.round(14f * dp));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(17f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        v.setTextColor(getColor(R.color.nightlight_gold));
        v.setGravity(Gravity.CENTER);
        card.addView(v);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11f);
        l.setTextColor(getColor(R.color.nightlight_cream_dim));
        l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Math.round(2f * dp);
        l.setLayoutParams(llp);
        card.addView(l);
        return card;
    }

    /** Total Playtime as XXh XXm (§23 display). */
    static String formatPlaytime(long ms) {
        long totalMin = Math.max(0, ms) / 60_000L;
        return String.format(Locale.US, "%dh %02dm", totalMin / 60, totalMin % 60);
    }

    // ---- playlists ----

    private void renderPlaylists() {
        addSectionTitle(getString(R.string.account_playlists));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        TextView loading = new TextView(this);
        loading.setText(R.string.lyrics_loading);
        loading.setTextColor(getColor(R.color.nightlight_cream_dim));
        loading.setTextSize(13f);
        list.addView(loading);
        AppExecutors.get().io().execute(() -> {
            List<Playlist> playlists = new ArrayList<>();
            try {
                NightLightApp app = (NightLightApp) getApplication();
                playlists = app.getPlaylistRepository().getPlaylistsSync();
                if (playlists == null) playlists = new ArrayList<>();
            } catch (Exception ignored) {
            }
            final List<Playlist> result = playlists;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                list.removeAllViews();
                if (result.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.account_no_playlists);
                    empty.setTextColor(getColor(R.color.nightlight_cream_dim));
                    empty.setTextSize(14f);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, Math.round(8f * dp), 0, Math.round(8f * dp));
                    list.addView(empty);
                } else {
                    for (Playlist p : result) list.addView(playlistCard(p));
                }
                TextView create = new TextView(this);
                create.setText(R.string.playlist_create_title);
                create.setTextSize(14f);
                create.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                create.setTextColor(getColor(R.color.nightlight_gold));
                create.setGravity(Gravity.CENTER);
                create.setPadding(0, Math.round(12f * dp), 0, Math.round(4f * dp));
                create.setOnClickListener(v -> PlaylistDialogs.showCreateDialog(
                        AccountActivity.this, getString(R.string.playlist_create_title), name -> {
                            NightLightApp app = (NightLightApp) getApplication();
                            app.getPlaylistRepository().createPlaylist(name, ok -> runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    content.removeAllViews();
                                    render();
                                }
                            }));
                        }));
                list.addView(create);
            });
        });
    }

    private View playlistCard(Playlist p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(cardBg());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10f * dp);
        card.setLayoutParams(lp);
        card.setPadding(Math.round(12f * dp), Math.round(12f * dp),
                Math.round(12f * dp), Math.round(12f * dp));

        ImageView cover = new ImageView(this);
        int coverSize = Math.round(56f * dp);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(coverSize, coverSize);
        coverLp.setMarginEnd(Math.round(14f * dp));
        cover.setLayoutParams(coverLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(Math.round(12f * dp));
        coverBg.setColor(Color.parseColor("#1AFFFFFF"));
        cover.setBackground(coverBg);
        cover.setClipToOutline(true);
        if (p.artworkUrl != null && !p.artworkUrl.isEmpty()) {
            Glide.with(this).load(p.artworkUrl).centerCrop()
                    .placeholder(R.drawable.nightlight_logo)
                    .error(R.drawable.nightlight_logo)
                    .into(cover);
        } else {
            cover.setImageResource(R.drawable.nightlight_logo);
            cover.setPadding(Math.round(12f * dp), Math.round(12f * dp),
                    Math.round(12f * dp), Math.round(12f * dp));
        }
        card.addView(cover);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(p.name != null ? p.name : "");
        name.setTextSize(16f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextColor(getColor(R.color.nightlight_cream));
        info.addView(name);
        TextView count = new TextView(this);
        count.setText(getString(R.string.account_playlist_count, p.trackCount));
        count.setTextSize(13f);
        count.setTextColor(getColor(R.color.nightlight_cream_dim));
        info.addView(count);
        card.addView(info);
        card.setOnClickListener(v -> startActivity(
                PlaylistActivity.intent(AccountActivity.this, p.id, p.name)));
        return card;
    }

    // ---- liked / recent ----

    private void renderTrackSection(String title, boolean liked) {
        addSectionTitle(title);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        NightLightApp app = (NightLightApp) getApplication();
        likedLive = liked
                ? app.getLibraryRepository().observeLikes()
                : app.getLibraryRepository().observeRecent();
        likedLive.observe(this, tracks -> {
            list.removeAllViews();
            if (tracks == null || tracks.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(liked ? R.string.account_no_likes : R.string.account_no_recent);
                empty.setTextColor(getColor(R.color.nightlight_cream_dim));
                empty.setTextSize(13f);
                list.addView(empty);
                return;
            }
            int n = Math.min(5, tracks.size());
            for (int i = 0; i < n; i++) list.addView(trackRow(tracks.get(i)));
        });
    }

    private View trackRow(Track t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8f * dp);
        row.setLayoutParams(lp);
        ImageView art = new ImageView(this);
        int s = Math.round(48f * dp);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(s, s);
        artLp.setMarginEnd(Math.round(12f * dp));
        art.setLayoutParams(artLp);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Math.round(10f * dp));
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        art.setBackground(bg);
        art.setClipToOutline(true);
        if (t.imageUrl != null && !t.imageUrl.isEmpty()) {
            Glide.with(this).load(t.imageUrl).centerCrop()
                    .placeholder(R.drawable.nightlight_logo)
                    .error(R.drawable.nightlight_logo)
                    .into(art);
        } else {
            art.setImageResource(R.drawable.nightlight_logo);
            art.setPadding(Math.round(10f * dp), Math.round(10f * dp),
                    Math.round(10f * dp), Math.round(10f * dp));
        }
        row.addView(art);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(t.name != null ? t.name : "");
        name.setTextSize(15f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextColor(getColor(R.color.nightlight_cream));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        TextView artist = new TextView(this);
        artist.setText(t.artists != null ? t.artists : "");
        artist.setTextSize(13f);
        artist.setTextColor(getColor(R.color.nightlight_cream_dim));
        artist.setMaxLines(1);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(artist);
        row.addView(info);
        row.setOnClickListener(v -> {
            java.util.List<Track> single = new java.util.ArrayList<>();
            single.add(t);
            TrackPlayer.play(AccountActivity.this, single, 0);
        });
        return row;
    }

    // ---- sign out ----

    private void renderSignOut() {
        TextView signOut = new TextView(this);
        signOut.setText(R.string.settings_sign_out);
        signOut.setTextSize(15f);
        signOut.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        signOut.setTextColor(getColor(R.color.nightlight_error));
        signOut.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(30f * dp);
        signOut.setLayoutParams(lp);
        signOut.setPadding(0, Math.round(14f * dp), 0, Math.round(14f * dp));
        signOut.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_sign_out)
                .setMessage(R.string.settings_sign_out_account_confirm)
                .setPositiveButton(R.string.settings_sign_out, (d, w) -> doSignOut())
                .setNegativeButton(R.string.action_cancel, null)
                .show());
        content.addView(signOut);
    }

    private void doSignOut() {
        AuthRepository auth = ((NightLightApp) getApplication()).getAuthRepository();
        auth.logout();
        Intent login = new Intent(this, LoginActivity.class);
        login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(login);
        finish();
    }
}

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.nightlight.app.BuildConfig;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.util.AccountPrefs;
import com.nightlight.app.util.TokenStore;

public final class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.activity_settings_root));

        NightLightApp app = (NightLightApp) getApplication();

        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        TextView version = findViewById(R.id.settings_version);
        version.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));

        wireDiscovery();
        wireDeveloper();
        wireAccount();

        findViewById(R.id.settings_clear_cache).setOnClickListener(v -> {
            Glide.get(this).clearMemory();
            new Thread(() -> {
                Glide.get(this).clearDiskCache();
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show());
            }).start();
        });

        findViewById(R.id.settings_clear_search_history).setOnClickListener(v -> {
            app.getLibraryRepository().clearSearchHistory();
            Toast.makeText(this, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.settings_clear_recent_history).setOnClickListener(v -> {
            app.getLibraryRepository().clearRecent();
            Toast.makeText(this, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.settings_licenses).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.settings_licenses)
                        .setMessage(R.string.licenses_text)
                        .setPositiveButton(R.string.action_close, null)
                        .show());

        findViewById(R.id.settings_privacy).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.settings_privacy)
                        .setMessage(R.string.settings_privacy_text)
                        .setPositiveButton(R.string.action_close, null)
                        .show());
    }

    private void wireDiscovery() {
        findViewById(R.id.settings_discovery_familiar).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.FAMILIAR);
            renderDiscovery();
        });
        findViewById(R.id.settings_discovery_balanced).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.BALANCED);
            renderDiscovery();
        });
        findViewById(R.id.settings_discovery_discovery).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.DISCOVERY);
            renderDiscovery();
        });
        renderDiscovery();
    }

    private void renderDiscovery() {
        String current = com.nightlight.app.util.ShufflePrefs.discovery(this);
        int[] rows = {R.id.settings_discovery_familiar, R.id.settings_discovery_balanced,
                R.id.settings_discovery_discovery};
        for (int row : rows) {
            TextView label = findViewById(row);
            boolean active = row == R.id.settings_discovery_familiar
                    ? com.nightlight.app.util.ShufflePrefs.FAMILIAR.equals(current)
                    : row == R.id.settings_discovery_balanced
                    ? com.nightlight.app.util.ShufflePrefs.BALANCED.equals(current)
                    : com.nightlight.app.util.ShufflePrefs.DISCOVERY.equals(current);
            label.setTextColor(getColor(active ? R.color.nightlight_gold : R.color.nightlight_cream));
            label.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void wireDeveloper() {
        openLink(R.id.settings_dev_linkedin, "https://www.linkedin.com/in/sagarakanoone/");
        openLink(R.id.settings_dev_github, "https://github.com/Sagar264offici/");
        openLink(R.id.settings_dev_portfolio, "https://sagar-horizon.vercel.app/");
        findViewById(R.id.settings_dev_email).setOnClickListener(v -> {
            android.content.Intent mail = new android.content.Intent(android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:pathaksagar264@gmail.com"));
            try {
                startActivity(mail);
            } catch (Exception e) {
                Toast.makeText(this, R.string.dev_email, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openLink(int rowId, String url) {
        findViewById(rowId).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Account section: avatar + name + status pill + sign in/out. Clean in all 3 states. */
    private void wireAccount() {
        final ViewGroup content = findViewById(R.id.settings_content);
        if (content == null) {
            return;
        }
        int dp = Math.round(getResources().getDisplayMetrics().density);

        TextView header = new TextView(this);
        header.setText(R.string.settings_account);
        header.setTextColor(getColor(R.color.nightlight_cream_dim));
        header.setTextSize(12f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.topMargin = Math.round(14f * dp);
        header.setLayoutParams(headerLp);

        // Outer card panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(0f);
        bg.setStroke(0, Color.TRANSPARENT);
        panel.setBackground(bg);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.topMargin = Math.round(4f * dp);
        panel.setLayoutParams(panelLp);
        int pad = Math.round(4f * dp);
        panel.setPadding(pad, Math.round(6f * dp), pad, Math.round(6f * dp));
        panel.setGravity(Gravity.CENTER_VERTICAL);

        // Circular avatar via ShapeableImageView + Glide circleCrop
        com.google.android.material.imageview.ShapeableImageView avatar = new com.google.android.material.imageview.ShapeableImageView(this);
        int avatarSize = Math.round(40f * dp);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd(Math.round(10f * dp));
        avatar.setLayoutParams(avatarLp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(
                avatar.getShapeAppearanceModel().withCornerSize(avatarSize / 2f));
        // Fallback: dark circle with person icon
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(Color.parseColor("#1AFFFFFF"));
        avatar.setBackground(avatarBg);
        avatar.setImageResource(R.drawable.ic_person_outline);
        avatar.setPadding(Math.round(18f * dp), Math.round(18f * dp),
                Math.round(18f * dp), Math.round(18f * dp));
        avatar.setColorFilter(getColor(R.color.nightlight_cream_dim));
        avatar.setContentDescription(getString(R.string.settings_account));
        panel.addView(avatar);

        // Right column: name + pill, subtitle, action
        LinearLayout infoColumn = new LinearLayout(this);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        infoColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean signedIn = TokenStore.hasToken();
        boolean guest = !signedIn && AccountPrefs.isGuest(this);
        String accountEmail = AccountPrefs.email(this);
        String displayName = AccountPrefs.profileName(this);
        String photo = AccountPrefs.photoUrl(this);

        // Load photo with Glide circleCrop if available
        if (signedIn && photo != null && !photo.isEmpty()) {
            Glide.with(this)
                    .load(photo)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person_outline)
                    .error(R.drawable.ic_person_outline)
                    .into(avatar);
            avatar.setPadding(0, 0, 0, 0);
            avatar.clearColorFilter();
        }

        // Title row: name (weight 1) + status pill
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nameText = new TextView(this);
        nameText.setTextSize(14f);
        nameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        nameText.setGravity(Gravity.START);
        nameText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (signedIn && displayName != null && !displayName.isEmpty()) {
            nameText.setText(displayName);
            nameText.setTextColor(getColor(R.color.nightlight_cream));
        } else if (signedIn && accountEmail != null) {
            nameText.setText(accountEmail);
            nameText.setTextColor(getColor(R.color.nightlight_cream));
        } else if (guest) {
            nameText.setText(R.string.settings_guest_name);
            nameText.setTextColor(getColor(R.color.nightlight_cream));
        } else {
            nameText.setText(R.string.settings_signed_out);
            nameText.setTextColor(getColor(R.color.nightlight_cream_dim));
        }
        titleRow.addView(nameText);

        // Status pill: MEMBER (gold) / GUEST (outline). Hidden when signed out.
        if (signedIn || guest) {
            TextView pill = new TextView(this);
            pill.setTextSize(10f);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            pill.setGravity(Gravity.CENTER);
            int pillHPad = Math.round(10f * dp);
            int pillVPad = Math.round(4f * dp);
            pill.setPadding(pillHPad, pillVPad, pillHPad, pillVPad);
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(Math.round(12f * dp));
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.setMarginStart(Math.round(8f * dp));
            pill.setLayoutParams(pillLp);
            if (signedIn) {
                pill.setText(R.string.settings_pill_member);
                pill.setTextColor(getColor(R.color.nightlight_gold));
                pillBg.setColor(Color.parseColor("#2662D35B"));
                pillBg.setStroke(Math.round(1f * dp), Color.parseColor("#6662D35B"));
            } else {
                pill.setText(R.string.settings_pill_guest);
                pill.setTextColor(getColor(R.color.nightlight_cream_dim));
                pillBg.setColor(Color.parseColor("#1AFFFFFF"));
                pillBg.setStroke(Math.round(1f * dp), Color.parseColor("#33FFFFFF"));
            }
            pill.setBackground(pillBg);
            titleRow.addView(pill);
        }
        infoColumn.addView(titleRow);

        // Subtitle: email for members, explainer for guest / signed-out
        TextView subtitle = new TextView(this);
        subtitle.setTextSize(11f);
        subtitle.setTextColor(getColor(R.color.nightlight_cream_dim));
        subtitle.setGravity(Gravity.START);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(2f * dp);
        subtitle.setLayoutParams(subLp);
        if (signedIn) {
            if (accountEmail != null && !accountEmail.equals(displayName)) {
                subtitle.setText(accountEmail);
                infoColumn.addView(subtitle);
            }
        } else if (guest) {
            subtitle.setText(R.string.settings_account_type_guest);
            infoColumn.addView(subtitle);
        } else {
            subtitle.setText(R.string.settings_signed_out_subtitle);
            infoColumn.addView(subtitle);
        }

        if (signedIn) {
            // Sign out action
            TextView signOut = new TextView(this);
            signOut.setText(R.string.settings_sign_out);
            signOut.setTextSize(14f);
            signOut.setTextColor(getColor(R.color.nightlight_error));
            signOut.setGravity(Gravity.START);
            LinearLayout.LayoutParams signOutLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            signOutLp.topMargin = Math.round(8f * dp);
            signOut.setLayoutParams(signOutLp);
            signOut.setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.settings_sign_out)
                        .setMessage(R.string.settings_sign_out_account_confirm)
                        .setPositiveButton(R.string.settings_sign_out, (d, w) -> doSignOut())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            });
            infoColumn.addView(signOut);
        } else {
            // Sign-in action: gold rounded button. Guests convert (playlists
            // offer to sync); signed-out users start at the entry screen.
            final boolean convertingGuest = guest;
            TextView signIn = new TextView(this);
            signIn.setText(R.string.settings_sign_in);
            signIn.setTextSize(14f);
            signIn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            signIn.setTextColor(Color.WHITE);
            signIn.setGravity(Gravity.CENTER);
            GradientDrawable signInBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{getColor(R.color.nightlight_gold), Color.parseColor("#48A943")});
            signInBg.setCornerRadius(Math.round(12f * dp));
            signIn.setBackground(signInBg);
            int btnHPad = Math.round(20f * dp);
            int btnVPad = Math.round(10f * dp);
            signIn.setPadding(btnHPad, btnVPad, btnHPad, btnVPad);
            LinearLayout.LayoutParams signInLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            signInLp.topMargin = Math.round(10f * dp);
            signIn.setLayoutParams(signInLp);
            signIn.setOnClickListener(v -> {
                if (convertingGuest) {
                    AccountPrefs.setPendingGuestConversion(this, true);
                }
                Intent login = new Intent(this, LoginActivity.class);
                login.putExtra(LoginActivity.EXTRA_MODE,
                        convertingGuest ? LoginActivity.MODE_CREATE : LoginActivity.MODE_ENTRY);
                startActivity(login);
            });
            infoColumn.addView(signIn);
        }

        panel.addView(infoColumn);
        // Full account page (photo, stats, playlists, liked, recent).
        panel.setOnClickListener(v -> startActivity(
                new Intent(this, com.nightlight.app.ui.AccountActivity.class)));
        content.addView(panel, 0);
        content.addView(header, 1);
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

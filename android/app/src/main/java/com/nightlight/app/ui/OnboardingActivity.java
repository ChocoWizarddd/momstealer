package com.nightlight.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.util.AccountPrefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * First-run onboarding: pick the languages you listen to, then the music you
 * like. Selections are persisted locally (AccountPrefs) and synced to the
 * account server-side (best effort). They seed the personalized Home context
 * when no explicit mood is chosen.
 */
public final class OnboardingActivity extends AppCompatActivity {

    private static final String[][] LANGUAGES = {
            {"Hindi", "Hindi"},
            {"English", "English"},
            {"Punjabi", "Punjabi"},
            {"Tamil", "Tamil"},
            {"Telugu", "Telugu"},
            {"Bengali", "Bengali"},
            {"Marathi", "Marathi"},
            {"Kannada", "Kannada"},
            {"Malayalam", "Malayalam"},
            {"Gujarati", "Gujarati"},
            {"Bhojpuri", "Bhojpuri"},
            {"Odia", "Odia"}
    };

    private static final String[][] CATEGORIES = {
            {"love", "Love"},
            {"chill", "Chill"},
            {"happy", "Happy"},
            {"sad", "Sad"},
            {"party", "Party"},
            {"workout", "Workout"},
            {"focus", "Focus"},
            {"pop", "Pop"},
            {"hip-hop", "Hip-Hop"},
            {"bollywood", "Bollywood"},
            {"indie", "Indie"},
            {"romantic", "Romantic"}
    };

    private AuthRepository auth;
    private LinearLayout root;
    private LinearLayout content;
    private TextView stepTitle;
    private TextView stepSubtitle;
    private TextView counter;
    private LinearLayout chipsRow;
    private TextView continueButton;
    private View stepDot1;
    private View stepDot2;

    private int step;
    private final Set<String> selected = new HashSet<>();
    // Language picks from step 1 must survive the step transition (renderStep
    // clears {@code selected}); only then can they be persisted alongside the
    // category picks when the flow finishes.
    private final Set<String> chosenLanguages = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = ((NightLightApp) getApplication()).getAuthRepository();
        buildUi();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.nightlight_navy_deep));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(28f * getResources().getDisplayMetrics().density);
        content.setPadding(pad, Math.round(48f * getResources().getDisplayMetrics().density), pad, pad);

        // Circular logo (MaterialCardView radius = half of size)
        int logoSize = Math.round(72f * getResources().getDisplayMetrics().density);
        com.google.android.material.card.MaterialCardView logoCard = new com.google.android.material.card.MaterialCardView(this);
        logoCard.setCardBackgroundColor(Color.TRANSPARENT);
        logoCard.setRadius(logoSize / 2f);
        logoCard.setCardElevation(0);
        logoCard.setStrokeWidth(0);
        LinearLayout.LayoutParams logoCardLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoCardLp.bottomMargin = Math.round(18f * getResources().getDisplayMetrics().density);
        logoCard.setLayoutParams(logoCardLp);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nightlight_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setLayoutParams(new com.google.android.material.card.MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logoCard.addView(logo);
        content.addView(logoCard);

        stepTitle = new TextView(this);
        stepTitle.setTextColor(getColor(R.color.nightlight_cream));
        stepTitle.setTextSize(22f);
        stepTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stepTitle.setGravity(Gravity.CENTER);
        stepTitle.setLetterSpacing(0.02f);
        stepTitle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(stepTitle);

        stepSubtitle = new TextView(this);
        stepSubtitle.setTextColor(getColor(R.color.nightlight_cream_dim));
        stepSubtitle.setTextSize(13f);
        stepSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        subLp.bottomMargin = Math.round(20f * getResources().getDisplayMetrics().density);
        stepSubtitle.setLayoutParams(subLp);
        content.addView(stepSubtitle);

        // Chip flow container.
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.VERTICAL);
        chipsRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(chipsRow);

        counter = new TextView(this);
        counter.setTextColor(getColor(R.color.nightlight_gold));
        counter.setTextSize(12f);
        counter.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = Math.round(6f * getResources().getDisplayMetrics().density);
        countLp.bottomMargin = Math.round(16f * getResources().getDisplayMetrics().density);
        counter.setLayoutParams(countLp);
        content.addView(counter);

        // Continue button.
        continueButton = new TextView(this);
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setTextColor(getColor(R.color.nightlight_navy_deep));
        continueButton.setTextSize(15f);
        continueButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        continueButton.setBackground(buttonBackground());
        continueButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(52f * getResources().getDisplayMetrics().density)));
        continueButton.setOnClickListener(v -> onContinue());
        content.addView(continueButton);

        // Progress dots.
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.topMargin = Math.round(18f * getResources().getDisplayMetrics().density);
        dots.setLayoutParams(dotsLp);

        stepDot1 = dot();
        dots.addView(stepDot1);
        int gap = Math.round(8f * getResources().getDisplayMetrics().density);
        ((LinearLayout.LayoutParams) stepDot1.getLayoutParams()).setMarginEnd(gap);
        stepDot2 = dot();
        dots.addView(stepDot2);
        content.addView(dots);

        scroller.addView(content);
        root.addView(scroller);
        setContentView(root);

        renderStep();
    }

    private View dot() {
        View dot = new View(this);
        int size = Math.round(8f * getResources().getDisplayMetrics().density);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#553B853D"));
        dot.setBackground(bg);
        dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return dot;
    }

    private GradientDrawable buttonBackground() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{getColor(R.color.nightlight_gold), getColor(R.color.nightlight_gold_dim)});
        bg.setCornerRadius(Math.round(16f * getResources().getDisplayMetrics().density));
        return bg;
    }

    private void renderStep() {
        boolean languagesStep = step == 0;
        stepTitle.setText(languagesStep ? R.string.onboarding_languages_title
                : R.string.onboarding_categories_title);
        stepSubtitle.setText(languagesStep ? R.string.onboarding_languages_subtitle
                : R.string.onboarding_categories_subtitle);
        continueButton.setText(languagesStep ? R.string.onboarding_next
                : R.string.onboarding_finish);

        selected.clear();
        chipsRow.removeAllViews();

        String[][] options = languagesStep ? LANGUAGES : CATEGORIES;
        LinearLayout row = null;
        int dp = Math.round(getResources().getDisplayMetrics().density);
        for (int i = 0; i < options.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                chipsRow.addView(row);
            }
            final String value = options[i][0];
            final String label = options[i][1];
            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(13.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_mood_chip);
            chip.setTextColor(getColor(R.color.nightlight_cream));
            int padH = Math.round(14f * dp);
            int padV = Math.round(10f * dp);
            chip.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.height = Math.round(44f * dp);
            lp.setMarginEnd(Math.round(10f * dp));
            lp.bottomMargin = Math.round(10f * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> toggleChip(chip, value));
            row.addView(chip);
        }

        updateStepDots();
        updateCounter();
        content.animate().alpha(0f).setDuration(110)
                .withEndAction(() -> {
                    content.setAlpha(1f);
                    content.animate().alpha(1f).setDuration(230).start();
                })
                .start();
    }

    private void toggleChip(TextView chip, String value) {
        boolean nowSelected;
        if (selected.contains(value)) {
            selected.remove(value);
            nowSelected = false;
        } else {
            selected.add(value);
            nowSelected = true;
        }
        // Press-in animation, then swap state styling.
        chip.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
                .withEndAction(() -> {
                    chip.setBackgroundResource(nowSelected
                            ? R.drawable.bg_mood_chip_selected : R.drawable.bg_mood_chip);
                    chip.setTextColor(getColor(nowSelected
                            ? R.color.nightlight_navy_deep : R.color.nightlight_cream));
                    chip.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                })
                .start();
        updateCounter();
    }

    private void updateCounter() {
        counter.setText(getString(R.string.onboarding_selected, selected.size()));
        boolean valid = !selected.isEmpty();
        continueButton.setAlpha(valid ? 1f : 0.45f);
        continueButton.setEnabled(valid);
    }

    private void updateStepDots() {
        ((GradientDrawable) stepDot1.getBackground()).setColor(
                step == 0 ? getColor(R.color.nightlight_gold) : Color.parseColor("#553B853D"));
        ((GradientDrawable) stepDot2.getBackground()).setColor(
                step == 1 ? getColor(R.color.nightlight_gold) : Color.parseColor("#553B853D"));
    }

    private void onContinue() {
        if (selected.isEmpty()) {
            return;
        }
        List<String> picked = new ArrayList<>(selected);
        if (step == 0) {
            chosenLanguages.addAll(selected);
            step = 1;
            renderStep();
        } else {
            List<String> languages = List.of(chosenLanguages.toArray(new String[0]));
            List<String> categories = categoriesFromSelected(picked);
            AccountPrefs.markOnboarded(this, languages, categories);
            auth.savePreferences(languages, categories);
            maybeOfferGuestConversion();
            Intent home = new Intent(this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
        }
    }

    /**
     * Guest conversion: when the guest came from the account-required prompt
     * (pending flag) and has device-local playlists, offer — never force — to
     * make them saved account playlists. Declining keeps them device-only.
     */
    private void maybeOfferGuestConversion() {
        if (!AccountPrefs.isPendingGuestConversion(this)) {
            return;
        }
        AccountPrefs.setPendingGuestConversion(this, false);
        com.nightlight.app.data.repo.PlaylistRepository playlists =
                ((NightLightApp) getApplication()).getPlaylistRepository();
        playlists.hasLocalPlaylists(hasLocal -> {
            if (!hasLocal) {
                return;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.guest_conversion_title)
                    .setMessage(R.string.guest_conversion_message)
                    .setPositiveButton(R.string.guest_conversion_save, (d, w) -> {
                        // Nothing to do: syncFromServer pushes local playlists
                        // for the now-authenticated user on next app start.
                    })
                    .setNegativeButton(R.string.guest_conversion_skip, (d, w) ->
                            AccountPrefs.setSkipLocalPlaylistPush(this, true))
                    .show();
        });
    }

    /** Maps the raw category keys to the user-facing names stored in prefs. */
    private List<String> categoriesFromSelected(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String[] cat : CATEGORIES) {
            if (keys.contains(cat[0])) {
                names.add(cat[1]);
            }
        }
        return names;
    }
}

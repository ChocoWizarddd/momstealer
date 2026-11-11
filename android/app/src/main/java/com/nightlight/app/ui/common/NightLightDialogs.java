package com.nightlight.app.ui.common;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.nightlight.app.R;

/**
 * NightLight-styled dialogs: dark rounded panels, cream typography, gold
 * primary actions. Replaces generic Android placeholder dialogs in
 * username, playlist, profile and import flows.
 */
public final class NightLightDialogs {

    private NightLightDialogs() {
    }

    public interface TextCallback {
        void onText(String text);
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable panelBg(Activity activity) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#202020"));
        bg.setCornerRadius(dp(activity, 20));
        bg.setStroke(dp(activity, 1), Color.parseColor("#33FFFFFF"));
        return bg;
    }

    private static GradientDrawable fieldBg(Activity activity) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        bg.setCornerRadius(dp(activity, 12));
        bg.setStroke(dp(activity, 1), Color.parseColor("#33FFFFFF"));
        return bg;
    }

    private static TextView primaryButton(Activity activity, String label, Runnable onClick) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setTextSize(14f);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{activity.getColor(R.color.nightlight_gold), Color.parseColor("#48A943")});
        bg.setCornerRadius(dp(activity, 12));
        button.setBackground(bg);
        int padV = dp(activity, 12);
        button.setPadding(dp(activity, 16), padV, dp(activity, 16), padV);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    /**
     * Themed single-field input dialog. Validation runs on Save: return an
     * error string to show inline, or null to accept and dismiss.
     */
    public static AlertDialog showInputDialog(
            Activity activity,
            String title,
            String message,
            String prefill,
            String hint,
            String positiveLabel,
            boolean cancelable,
            java.util.function.Function<String, String> validate,
            TextCallback callback) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(panelBg(activity));
        int pad = dp(activity, 20);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(titleView);

        if (message != null && !message.isEmpty()) {
            TextView messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
            messageView.setTextSize(13f);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = dp(activity, 6);
            messageView.setLayoutParams(msgLp);
            root.addView(messageView);
        }

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(activity.getColor(R.color.nightlight_cream_dim));
        input.setHint(hint);
        input.setTextSize(15f);
        input.setBackground(fieldBg(activity));
        int fieldPad = dp(activity, 14);
        input.setPadding(fieldPad, fieldPad, fieldPad, fieldPad);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(activity, 14);
        input.setLayoutParams(inputLp);
        if (prefill != null && !prefill.isEmpty()) {
            input.setText(prefill);
            input.setSelection(input.getText() != null ? input.getText().length() : 0);
        }
        root.addView(input);

        TextView errorView = new TextView(activity);
        errorView.setTextColor(activity.getColor(R.color.nightlight_error));
        errorView.setTextSize(12f);
        errorView.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = dp(activity, 6);
        errorView.setLayoutParams(errLp);
        root.addView(errorView);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(activity, 16);
        buttons.setLayoutParams(btnRowLp);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(cancelable)
                .create();
        // Transparent window so our rounded panel shape shows.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        if (cancelable) {
            TextView cancel = new TextView(activity);
            cancel.setText(activity.getString(R.string.action_cancel));
            cancel.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
            cancel.setTextSize(14f);
            cancel.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
            cancel.setOnClickListener(v -> dialog.dismiss());
            buttons.addView(cancel);
        }
        buttons.addView(primaryButton(activity, positiveLabel, () -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            String error = validate != null ? validate.apply(text) : null;
            if (error != null && !error.isEmpty()) {
                errorView.setText(error);
                errorView.setVisibility(android.view.View.VISIBLE);
                return;
            }
            dialog.dismiss();
            callback.onText(text);
        }));
        root.addView(buttons);

        dialog.show();
        input.post(() -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        return dialog;
    }

    /** Themed confirmation with gold confirm + quiet cancel. */
    public static void showConfirm(
            Activity activity,
            String title,
            String message,
            String confirmLabel,
            Runnable onConfirm) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(panelBg(activity));
        int pad = dp(activity, 20);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(titleView);

        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
        messageView.setTextSize(13f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(activity, 6);
        messageView.setLayoutParams(msgLp);
        root.addView(messageView);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(activity, 16);
        buttons.setLayoutParams(btnRowLp);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.action_cancel));
        cancel.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
        cancel.setTextSize(14f);
        cancel.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel);
        buttons.addView(primaryButton(activity, confirmLabel, () -> {
            dialog.dismiss();
            onConfirm.run();
        }));
        root.addView(buttons);
        dialog.show();
    }
}

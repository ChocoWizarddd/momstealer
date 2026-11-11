package com.nightlight.app.util;

import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Edge-to-edge helper: pads a root view for system bars. */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /**
     * Adds the status-bar inset to the top and the navigation-bar inset to the
     * bottom of the given view, preserving its existing left/right padding.
     */
    public static void applySystemBars(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), systemBars.top, v.getPaddingRight(), systemBars.bottom);
            return insets;
        });
    }
}
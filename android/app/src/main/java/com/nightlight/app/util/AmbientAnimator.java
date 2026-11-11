package com.nightlight.app.util;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The NightLight ambient motion system. One shared animator drives the
 * atmospheric "breathing" of a screen — artwork scale only. Opacity loops
 * are intentionally NOT used: overlapping alpha animations on the backdrop
 * and glow read as blinking/flicker while a song plays, so all screens stay
 * at fixed alpha. Everything is lifecycle-safe: callers must call
 * {@link #start} when the screen becomes visible and {@link #stop} when it
 * hides or is destroyed — the animator then releases its target references
 * so nothing leaks.
 */
public final class AmbientAnimator {

    private final List<Animator> animators = new ArrayList<>();
    private final List<View> targets = new ArrayList<>();
    private boolean running;

    private AmbientAnimator() {
    }

    /**
     * Builds the ambient set around the main artwork. Backdrop/glow views are
     * accepted for API compatibility but intentionally left static — alpha
     * loops on them blink while music plays. Only the artwork keeps a subtle
     * scale breath. Views may be null — they are skipped.
     */
    public static AmbientAnimator forNowPlaying(View artwork, View backdrop, View glow) {
        AmbientAnimator a = new AmbientAnimator();
        long period = 6000;

        if (artwork != null) {
            // Breathing artwork: subtle scale loop (balanced).
            a.targets.add(artwork);
            ObjectAnimator sx = ObjectAnimator.ofFloat(artwork, View.SCALE_X, 1f, 1.022f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(artwork, View.SCALE_Y, 1f, 1.022f);
            for (ObjectAnimator an : new ObjectAnimator[]{sx, sy}) {
                an.setDuration(period);
                an.setRepeatCount(ValueAnimator.INFINITE);
                an.setRepeatMode(ValueAnimator.REVERSE);
                an.setInterpolator(new AccelerateDecelerateInterpolator());
                a.animators.add(an);
            }
        }
        // NOTE: backdrop + glow stay at their layout alpha (no animation).
        return a;
    }

    /** Gently brings a view in with a soft flourish (entrances). */
    public static void enter(View v) {
        if (v == null) {
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.97f);
        v.setScaleY(0.97f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /** Starts (or restarts) all animations. Safe to call repeatedly. */
    public void start() {
        if (running || animators.isEmpty()) {
            return;
        }
        running = true;
        for (Animator a : animators) {
            a.start();
        }
    }

    /** Cancels everything and releases target references (no leaks). */
    public void stop() {
        running = false;
        for (Animator a : animators) {
            a.cancel();
        }
        animators.clear();
        // Reset visual state so a paused screen never freezes mid-breathe.
        for (View v : targets) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
        targets.clear();
    }

    public boolean isRunning() {
        return running;
    }
}

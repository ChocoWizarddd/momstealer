package com.nightlight.app.ui.common;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Artwork surface used by the full player. The historical class name is kept
 * because the player and its artwork loader already depend on this view.
 * Album art is drawn as a rounded square; playback adds a very subtle scale
 * breathe instead of rotating the cover.
 */
public final class VinylView extends View {

    private static final long BREATH_MS = 4_800L;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final RectF drawRect = new RectF();

    private Bitmap artwork;
    private ValueAnimator spinner;

    public VinylView(Context context) {
        super(context);
        init(context);
    }

    public VinylView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** Replace the active track artwork. */
    public void setArtwork(Bitmap bitmap) {
        artwork = bitmap;
        invalidate();
    }

    /** Gentle breathing scale while playing, paused with playback. */
    public void setSpinning(boolean spinning) {
        if (spinning) {
            if (spinner == null) {
                spinner = ValueAnimator.ofFloat(0f, 1f);
                spinner.setDuration(BREATH_MS);
                spinner.setRepeatCount(ValueAnimator.INFINITE);
                spinner.setRepeatMode(ValueAnimator.REVERSE);
                spinner.setInterpolator(new LinearInterpolator());
                spinner.addUpdateListener(a -> {
                    float pulse = (float) a.getAnimatedValue();
                    float scale = 0.992f + pulse * 0.008f;
                    setScaleX(scale);
                    setScaleY(scale);
                });
            }
            if (!spinner.isStarted()) {
                spinner.start();
            } else if (spinner.isPaused()) {
                spinner.resume();
            }
        } else if (spinner != null && spinner.isStarted() && !spinner.isPaused()) {
            spinner.pause();
        }
    }

    public void stopSpinning() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
        setScaleX(1f);
        setScaleY(1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float inset = getResources().getDisplayMetrics().density * 4f;
        float radius = getResources().getDisplayMetrics().density * 8f;
        drawRect.set(inset, inset, w - inset, h - inset);
        clipPath.rewind();
        clipPath.addRoundRect(drawRect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        if (artwork != null && !artwork.isRecycled()) {
            canvas.drawBitmap(artwork, null, drawRect, bitmapPaint);
        } else {
            canvas.drawColor(0xFF282828);
        }
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpinning();
        super.onDetachedFromWindow();
    }
}

package com.nightlight.app.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Compact progress control. The track and thumb follow playback state, while
 * taps/drags seek through the active Media3 item. The existing class name is
 * retained to keep its layout and controller wiring stable.
 * Played portion uses the NightLight leaf accent; the rest stays dim. Tap or drag to seek; the
 * host is told only on release, while the fill follows the finger live.
 */
public final class WaveformSeekView extends View {

    public interface SeekListener {
        void onSeek(float fraction);
    }

    private static final int ACCENT = 0xFF62D35B;
    private static final int DIM = 0x40FFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int max = 1000;
    private int progress;
    private boolean dragging;
    private float dragFraction = -1f;
    private SeekListener listener;

    public WaveformSeekView(Context context) {
        super(context);
        init();
    }

    public WaveformSeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        setContentDescription("Playback position");
        setFocusable(true);
    }

    /** Kept for the current player call sites; progress no longer uses per-track waveforms. */
    public void setShape(long seed) {
        invalidate();
    }

    public void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(max, progress));
        if (!dragging) {
            dragFraction = -1f;
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setSeekListener(SeekListener listener) {
        this.listener = listener;
    }

    private float shownFraction() {
        if (dragFraction >= 0f) return dragFraction;
        return max <= 0 ? 0f : (float) progress / (float) max;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float inset = getResources().getDisplayMetrics().density * 2f;
        float left = inset;
        float right = w - inset;
        float frac = shownFraction();
        float centerY = h / 2f;
        paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
        paint.setColor(DIM);
        canvas.drawLine(left, centerY, right, centerY, paint);
        float progressX = left + (right - left) * frac;
        paint.setColor(ACCENT);
        canvas.drawLine(left, centerY, progressX, centerY, paint);
        canvas.drawCircle(progressX, centerY, getResources().getDisplayMetrics().density * 5f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                dragFraction = fractionOf(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                dragFraction = fractionOf(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = dragging;
                dragging = false;
                float f = dragFraction >= 0f ? dragFraction : fractionOf(event.getX());
                dragFraction = -1f;
                invalidate();
                if (wasDragging && event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                    listener.onSeek(f);
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private float fractionOf(float x) {
        int w = getWidth();
        if (w <= 0) return 0f;
        return Math.max(0f, Math.min(1f, x / (float) w));
    }
}

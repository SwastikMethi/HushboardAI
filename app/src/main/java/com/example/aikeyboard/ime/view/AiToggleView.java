// The AI control in the keyboard's tool row: a sparkle and the words "AI on" or "AI off".
// Off is neutral grey. On shows the violet-to-blue AI identity, steadily. A single 600 ms
// sweep plays only when the user has just turned AI on, never on redraw or rotation.
package com.example.aikeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;

import com.example.aikeyboard.R;

public final class AiToggleView extends View {

    /** Shared motion token: one sweep, this long (docs/UI_SPEC.md). */
    public static final long ENABLE_SWEEP_MS = 600;

    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sparkle = new Path();
    private final Path pillClip = new Path();
    private final RectF pill = new RectF();
    private final int gradientStart;
    private final int gradientEnd;
    private final int neutral;
    private final float strokeWidth;
    private final float iconSize;
    private final float gap;
    private final float sidePadding;

    private boolean on;
    /** Position of the enable sweep from 0 to 1, or negative when it is not running. */
    private float sweep = -1f;
    private ValueAnimator animator;

    public AiToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gradientStart = context.getColor(R.color.ai_gradient_start);
        gradientEnd = context.getColor(R.color.ai_gradient_end);
        neutral = context.getColor(R.color.control_disabled);
        strokeWidth = dp(1.5f);
        iconSize = dp(16);
        gap = dp(6);
        sidePadding = dp(12);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(strokeWidth);
        label.setColor(context.getColor(R.color.key_label));
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        setClickable(true);
        setOn(false);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** Shows the saved state. Never animates: use {@link #playEnableSweep()} for that. */
    public void setOn(boolean enabled) {
        on = enabled;
        setContentDescription(getContext().getString(
                enabled ? R.string.ai_toggle_on : R.string.ai_toggle_off));
        requestLayout();
        invalidate();
    }

    /**
     * Plays the one-time sweep. Call only when the user has just turned AI on and the
     * setting is saved. Does nothing when the system has animations switched off.
     */
    public void playEnableSweep() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(ENABLE_SWEEP_MS);
        animator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            if (sweep >= 1f) sweep = -1f;
            invalidate();
        });
        animator.start();
    }

    /** Whether the enable sweep is playing right now. */
    public boolean isSweeping() {
        return animator != null && animator.isRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        sweep = -1f;
        super.onDetachedFromWindow();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }

    private String text() {
        return getContext().getString(on ? R.string.ai_on : R.string.ai_off);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float width = sidePadding * 2 + iconSize + gap + label.measureText(text());
        setMeasuredDimension((int) Math.ceil(width), getDefaultSize(0, heightSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float half = strokeWidth / 2;
        pill.set(half, half, w - half, h - half);
        pillClip.reset();
        pillClip.addRoundRect(pill, h / 2f, h / 2f, Path.Direction.CW);
        // A four-pointed sparkle in a box of iconSize, centred vertically at the left.
        float cx = sidePadding + iconSize / 2;
        float cy = h / 2f;
        float r = iconSize / 2;
        float pinch = r * 0.18f;
        sparkle.reset();
        sparkle.moveTo(cx, cy - r);
        sparkle.quadTo(cx + pinch, cy - pinch, cx + r, cy);
        sparkle.quadTo(cx + pinch, cy + pinch, cx, cy + r);
        sparkle.quadTo(cx - pinch, cy + pinch, cx - r, cy);
        sparkle.quadTo(cx - pinch, cy - pinch, cx, cy - r);
        sparkle.close();
        Shader gradient = new LinearGradient(0, 0, w, 0, gradientStart, gradientEnd,
                Shader.TileMode.CLAMP);
        border.setShader(null);
        icon.setShader(null);
        activeShader = gradient;
    }

    private Shader activeShader;

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float radius = getHeight() / 2f;
        if (on) {
            border.setShader(activeShader);
            icon.setShader(activeShader);
        } else {
            border.setShader(null);
            icon.setShader(null);
            border.setColor(neutral);
            icon.setColor(neutral);
        }
        canvas.drawRoundRect(pill, radius, radius, border);
        canvas.drawPath(sparkle, icon);
        float baseline = getHeight() / 2f - (label.ascent() + label.descent()) / 2;
        canvas.drawText(text(), sidePadding + iconSize + gap, baseline, label);

        if (sweep >= 0f) {
            // A soft band of light crossing the control once, left to right.
            float band = getWidth() * 0.45f;
            float x = -band + (getWidth() + band) * sweep;
            shine.setShader(new LinearGradient(x, 0, x + band, 0,
                    new int[] {Color.TRANSPARENT, 0x66FFFFFF, Color.TRANSPARENT}, null,
                    Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipPath(pillClip);
            canvas.drawRect(x, 0, x + band, getHeight(), shine);
            canvas.restore();
        }
    }
}

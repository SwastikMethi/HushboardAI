// The level meter Kids Chat shows while a child speaks: a row of bars whose heights follow
// the last few moments of loudness, so the child can see they are being heard. Decoration:
// it carries no meaning a screen reader needs.
package com.example.aikeyboard.ui.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.example.aikeyboard.R;

public final class KidsLevelView extends View {

    private static final int BARS = 8;

    // The loudness of the last BARS moments, between 0 and 1, oldest at `oldest`.
    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int oldest;

    public KidsLevelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(context.getColor(R.color.kids_mia));
    }

    /** Adds the newest moment of loudness, between 0 and 1, and lets go of the oldest. */
    public void push(float level) {
        levels[oldest] = Math.max(0, Math.min(1, level));
        oldest = (oldest + 1) % BARS;
        invalidate();
    }

    public void reset() {
        java.util.Arrays.fill(levels, 0);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float slot = getWidth() / (float) BARS;
        float bar = slot * 0.55f;
        for (int i = 0; i < BARS; i++) {
            // Speech is quiet in absolute terms: the square root lifts ordinary talking
            // into bars that can be seen, and a bar never shrinks below a dot.
            float loud = (float) Math.sqrt(levels[(oldest + i) % BARS]);
            float height = Math.max(bar, loud * getHeight());
            float left = i * slot + (slot - bar) / 2;
            float top = (getHeight() - height) / 2;
            canvas.drawRoundRect(left, top, left + bar, top + height, bar / 2, bar / 2, paint);
        }
    }
}

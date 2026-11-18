// Draws a hidden detail in a Kids Chat message, such as "[email]", as a white pill with a
// lock and the word. Only the drawing changes: the text underneath stays the plain bracketed
// word, so what a screen reader says and what the tests read are the message itself.
package com.example.aikeyboard.ui.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.example.aikeyboard.R;

public final class LockChipSpan extends ReplacementSpan {

    // All as a share of the text size, so the chip grows with the child's font setting.
    private static final float EDGE = 0.45f;
    private static final float ICON = 0.85f;
    private static final float GAP = 0.2f;

    private final Drawable lock;
    private final int fill;
    private final int ink;
    private final RectF pill = new RectF();

    private LockChipSpan(Context context) {
        lock = AppCompatResources.getDrawable(context, R.drawable.ic_kids_lock);
        fill = context.getColor(R.color.kids_card);
        ink = context.getColor(R.color.kids_ink);
    }

    /** The message with every hidden detail in it drawn as a chip. */
    public static CharSequence decorate(Context context, String message) {
        SpannableString shown = new SpannableString(message);
        for (String word : ChatChecks.hiddenWords()) {
            for (int at = message.indexOf(word); at >= 0;
                    at = message.indexOf(word, at + word.length())) {
                shown.setSpan(new LockChipSpan(context), at, at + word.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return shown;
    }

    // The brackets are not drawn: a lock, a gap and the word, with room at both ends.
    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
            @Nullable Paint.FontMetricsInt metrics) {
        return Math.round(paint.getTextSize() * (EDGE + ICON + GAP + EDGE)
                + paint.measureText(text, start + 1, end - 1));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
            int top, int y, int bottom, @NonNull Paint paint) {
        float size = paint.getTextSize();
        int textColor = paint.getColor();
        // As tall as the letters with a little air, not as tall as the whole line.
        pill.set(x, y - size * 1.05f, x + getSize(paint, text, start, end, null),
                y + size * 0.4f);
        paint.setColor(fill);
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint);

        int icon = Math.round(size * ICON);
        int left = Math.round(x + size * EDGE);
        int iconTop = Math.round(pill.centerY() - icon / 2f);
        lock.setBounds(left, iconTop, left + icon, iconTop + icon);
        lock.draw(canvas);

        paint.setColor(ink);
        canvas.drawText(text, start + 1, end - 1, left + icon + size * GAP, y, paint);
        paint.setColor(textColor);
    }
}

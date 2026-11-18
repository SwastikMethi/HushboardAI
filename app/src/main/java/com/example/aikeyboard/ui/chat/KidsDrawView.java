// The paper of Kids Chat's drawing pad: any number of strokes, each in the crayon it was
// drawn with. A stroke the shape model recognises is shown as the tidy shape in place of the
// wobbly line. The drawing lives in this view only and is gone when the screen is rebuilt.
package com.example.aikeyboard.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.example.aikeyboard.R;
import com.example.aikeyboard.media.Vision.RecognizedShape;

import java.util.ArrayList;
import java.util.List;

public final class KidsDrawView extends View {

    /** Told about each finished stroke, and whenever what is on the paper changes. */
    public interface Listener {
        /** @param stroke stands for this stroke when a tidy shape for it comes back */
        void onStroke(Object stroke, List<float[]> points);

        void onChanged();
    }

    private static final class Stroke {
        final int color;
        final List<float[]> points = new ArrayList<>();
        /** The shape this stroke was recognised as, or null while it is shown as drawn. */
        RecognizedShape tidy;

        Stroke(int color) {
            this.color = color;
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    // Points closer together than this add nothing but work for the shape model.
    private final float minStep;
    private Stroke underFinger;
    private int color;
    private Listener listener;

    public KidsDrawView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        minStep = 2 * dp;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5 * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        color = context.getColor(R.color.kids_ink);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The crayon for the strokes drawn from now on. */
    public void setColor(int color) {
        this.color = color;
    }

    public boolean isEmpty() {
        return strokes.isEmpty();
    }

    public int strokeCount() {
        return strokes.size();
    }

    /** Takes the last stroke off the paper. */
    public void undo() {
        if (strokes.isEmpty()) return;
        strokes.remove(strokes.size() - 1);
        changed();
    }

    public void clear() {
        strokes.clear();
        underFinger = null;
        changed();
    }

    /**
     * Shows a finished stroke as the shape it was recognised as. Nothing happens if the
     * stroke has been undone or cleared in the meantime.
     */
    public void tidy(Object stroke, RecognizedShape shape) {
        if (!strokes.contains(stroke)) return;
        ((Stroke) stroke).tidy = shape;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The pad sits in a screen that can scroll; a stroke must not scroll it.
                getParent().requestDisallowInterceptTouchEvent(true);
                underFinger = new Stroke(color);
                underFinger.points.add(new float[] {x, y});
                return true;
            case MotionEvent.ACTION_MOVE:
                if (underFinger == null) return true;
                float[] last = underFinger.points.get(underFinger.points.size() - 1);
                if (Math.hypot(x - last[0], y - last[1]) >= minStep) {
                    underFinger.points.add(new float[] {x, y});
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                Stroke done = underFinger;
                underFinger = null;
                // A tap leaves no mark: a stroke needs somewhere to go.
                if (done != null && done.points.size() >= 2) {
                    strokes.add(done);
                    if (listener != null) listener.onStroke(done, new ArrayList<>(done.points));
                    changed();
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                underFinger = null;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, underFinger);
    }

    /** The drawing as a picture to send: every stroke as it is shown, on white. */
    public Bitmap render() {
        Bitmap picture = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(picture);
        canvas.drawColor(Color.WHITE);
        draw(canvas, null);
        return picture;
    }

    private void draw(Canvas canvas, Stroke unfinished) {
        for (Stroke stroke : strokes) draw(canvas, stroke, paint);
        if (unfinished != null) draw(canvas, unfinished, paint);
    }

    private void draw(Canvas canvas, Stroke stroke, Paint with) {
        with.setColor(stroke.color);
        if (stroke.tidy == null) {
            canvas.drawPath(line(stroke.points, false), with);
            return;
        }
        canvas.drawPath(line(stroke.tidy.outline, stroke.tidy.closed()), with);
        for (List<float[]> extra : stroke.tidy.extras) canvas.drawPath(line(extra, false), with);
    }

    private Path line(List<float[]> points, boolean closed) {
        path.rewind();
        for (int i = 0; i < points.size(); i++) {
            float[] point = points.get(i);
            if (i == 0) path.moveTo(point[0], point[1]);
            else path.lineTo(point[0], point[1]);
        }
        if (closed && !points.isEmpty()) path.close();
        return path;
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onChanged();
    }
}

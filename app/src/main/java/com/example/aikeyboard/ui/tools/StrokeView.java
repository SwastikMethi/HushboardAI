// A surface for drawing one stroke with a finger. It reports the stroke when the finger
// lifts, and can show the cleaned-up shape the stroke was recognised as on top of it. Used by
// the Drawing screen and by the keyboard's drawing panel.
package com.example.aikeyboard.ui.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import com.example.aikeyboard.R;
import com.example.aikeyboard.media.Vision.RecognizedShape;

import java.util.ArrayList;
import java.util.List;

public final class StrokeView extends View {

    public interface Listener {
        /** The finger lifted. Points are {x, y} in this view's pixels, in drawing order. */
        void onStroke(List<float[]> points);
    }

    // Closer together than this, a new point adds nothing but work for the model.
    private static final float MIN_STEP_PX = 4;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> points = new ArrayList<>();
    private RecognizedShape shape;
    private Listener listener;

    public StrokeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4,
                getResources().getDisplayMetrics());
        for (Paint paint : new Paint[] {strokePaint, shapePaint}) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        strokePaint.setColor(context.getColor(R.color.key_label_secondary));
        shapePaint.setColor(context.getColor(R.color.accent_blue));
        setContentDescription(context.getString(R.string.drawing_surface));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Shows the cleaned-up shape over the stroke, or only the stroke when null. */
    public void setShape(RecognizedShape recognized) {
        shape = recognized;
        invalidate();
    }

    public void clear() {
        points.clear();
        shape = null;
        invalidate();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    // A plain view asks for all the height on offer. This one asks for none unless it is told
    // an exact height, so the layout around it decides (it is given what is left over). In
    // the keyboard that is what keeps the drawing panel the height of the keyboard instead
    // of the whole screen.
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightSpec) : getSuggestedMinimumHeight());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Disabled while there is no model to look at a stroke: nothing to draw for.
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // One stroke at a time: a new one replaces the last.
                points.clear();
                shape = null;
                points.add(new float[] {event.getX(), event.getY()});
                // Keep the stroke from being taken over as a scroll by the screen around it.
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getHistorySize(); i++) {
                    add(event.getHistoricalX(i), event.getHistoricalY(i));
                }
                add(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_UP:
                add(event.getX(), event.getY());
                if (listener != null) listener.onStroke(new ArrayList<>(points));
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                points.clear();
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    // Finishing a stroke counts as the view's "click" for accessibility services.
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void add(float x, float y) {
        if (points.isEmpty()) return;
        float[] last = points.get(points.size() - 1);
        if (Math.hypot(x - last[0], y - last[1]) >= MIN_STEP_PX) {
            points.add(new float[] {x, y});
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Once a shape is recognised the hand-drawn stroke fades back behind it.
        strokePaint.setAlpha(shape == null ? 255 : 70);
        canvas.drawPath(path(points, false), strokePaint);
        if (shape != null) drawShape(canvas, shapePaint);
    }

    private void drawShape(Canvas canvas, Paint paint) {
        canvas.drawPath(path(shape.outline, shape.closed()), paint);
        for (List<float[]> extra : shape.extras) canvas.drawPath(path(extra, false), paint);
    }

    private static Path path(List<float[]> line, boolean closed) {
        Path path = new Path();
        for (int i = 0; i < line.size(); i++) {
            float[] point = line.get(i);
            if (i == 0) path.moveTo(point[0], point[1]);
            else path.lineTo(point[0], point[1]);
        }
        if (closed && !line.isEmpty()) path.close();
        return path;
    }

    /**
     * The drawing as a picture to share or insert: the cleaned-up shape if there is one,
     * otherwise the stroke as drawn, in dark ink on white so it reads in any chat.
     */
    public Bitmap render() {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint ink = new Paint(shapePaint);
        ink.setColor(Color.BLACK);
        if (shape != null) drawShape(canvas, ink);
        else canvas.drawPath(path(points, false), ink);
        return bitmap;
    }
}

// Shapes: recognises one hand-drawn stroke as a shape and returns a clean outline of it. It
// tidies drawings; it does not read handwriting.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.RecognizedShape;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import ai.desertant.shapes.Options;
import ai.desertant.shapes.Point;
import ai.desertant.shapes.Shape;
import ai.desertant.shapes.Shapes;

public final class ShapesAdapter implements ModelAdapter<List<float[]>, RecognizedShape> {

    private static final String ID = "shapes";
    private static final long TIMEOUT_SECONDS = 30;
    // In the stroke's own units (screen pixels): big enough to read as an arrow.
    private static final double ARROWHEAD_LENGTH = 36;

    private final ModelRuntimePool pool;

    public ShapesAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    /** @param stroke points as {x, y}, in drawing order. Returns null for less than a line. */
    @Override
    public RecognizedShape run(List<float[]> stroke, AppSettings settings) throws Exception {
        if (stroke.size() < 2) return null;
        Shapes shapes = pool.acquire(ID, Shapes::new, Shapes::isDownloaded);
        List<Point> points = new ArrayList<>(stroke.size());
        for (float[] point : stroke) points.add(new Point(point[0], point[1]));
        Shape shape = DesertAntClient.<Shape>run(
                c -> shapes.recognize(points, new Options(), c))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.answered(ID);
        if (shape == null) return null;
        List<List<float[]>> extras = new ArrayList<>();
        if (shape instanceof Shape.Arrow) {
            for (List<Point> head : ((Shape.Arrow) shape).arrowheads(ARROWHEAD_LENGTH)) {
                extras.add(toPoints(head));
            }
        }
        return new RecognizedShape(shape.getKind().name().toLowerCase(Locale.ROOT),
                toPoints(shape.getOutline()), extras);
    }

    private static List<float[]> toPoints(List<Point> points) {
        List<float[]> converted = new ArrayList<>(points.size());
        for (Point point : points) {
            converted.add(new float[] {(float) point.getX(), (float) point.getY()});
        }
        return converted;
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

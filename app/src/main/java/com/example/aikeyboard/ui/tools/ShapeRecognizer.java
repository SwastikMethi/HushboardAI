// Runs the shape model on a stroke and reports back on the main thread, dropping the answer
// if a newer stroke (or a clear) has replaced the one it was for. Shared by the Drawing
// screen and the keyboard's drawing panel so both behave the same.
package com.example.aikeyboard.ui.tools;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.RecognizedShape;

import java.util.List;
import java.util.concurrent.Executor;

public final class ShapeRecognizer {

    public interface Listener {
        /**
         * @param shape the recognised shape, or null when the stroke is no clear shape
         * @param failed true when the model itself could not run
         */
        void onResult(RecognizedShape shape, boolean failed);
    }

    private final AppContainer app;
    private final Executor main;
    private int generation;

    public ShapeRecognizer(Context context) {
        app = AppContainer.from(context);
        main = ContextCompat.getMainExecutor(context);
    }

    /** Whether the shape model is switched on and installed. */
    public boolean usable() {
        AppSettings settings = app.settings.latest();
        return settings.modelActive("shapes") && app.models.isReady("shapes");
    }

    /** Main thread. The answer arrives on the main thread, unless it has been superseded. */
    public void recognise(List<float[]> stroke, Listener listener) {
        int asked = ++generation;
        AppSettings settings = app.settings.latest();
        // The one inference thread: this and suggestions while typing take turns.
        app.inference.execute(() -> {
            RecognizedShape shape = null;
            boolean failed = false;
            try {
                shape = app.shapes.run(stroke, settings);
            } catch (Exception e) {
                failed = true;
            }
            // A scribble is the model saying "no shape"; it is not something to tidy up.
            RecognizedShape found = shape != null && "scribble".equals(shape.kind) ? null : shape;
            boolean modelFailed = failed;
            main.execute(() -> {
                if (asked == generation) listener.onResult(found, modelFailed);
            });
        });
    }

    /** Makes any answer still on its way unwanted (the drawing was cleared or left). */
    public void forget() {
        generation++;
    }

    /** The name to show for a recognised shape, in the app's own words. */
    public static int nameOf(String kind) {
        switch (kind) {
            case "line":
                return R.string.shape_line;
            case "curve":
                return R.string.shape_curve;
            case "arrow":
                return R.string.shape_arrow;
            case "rectangle":
                return R.string.shape_rectangle;
            case "ellipse":
                return R.string.shape_ellipse;
            case "triangle":
                return R.string.shape_triangle;
            case "diamond":
                return R.string.shape_diamond;
            case "pentagon":
                return R.string.shape_pentagon;
            case "hexagon":
                return R.string.shape_hexagon;
            case "star":
                return R.string.shape_star;
            case "heart":
                return R.string.shape_heart;
            case "cloud":
                return R.string.shape_cloud;
            default:
                return R.string.shape_other;
        }
    }
}

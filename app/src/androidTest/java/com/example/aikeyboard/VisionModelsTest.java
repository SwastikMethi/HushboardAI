// The image and drawing models on a device, through the app's own download and adapters:
// Eye on a real screenshot, Moderator on a harmless picture, Shapes on strokes drawn by
// formula. No explicit imagery is used anywhere, so Moderator's "flagged" path is reached by
// lowering its threshold, not by showing it something it should flag.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.RecognizedShape;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.media.Vision.Tag;
import com.example.aikeyboard.models.ModelRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class VisionModelsTest {

    private static final String TAG = "VisionModels";

    private AppContainer app;

    @Before
    public void setUp() {
        app = TestModels.container();
    }

    private static AppSettings defaults() {
        return new AppSettings(1, Map.of());
    }

    private <T> T onInferenceThread(Callable<T> work) throws Exception {
        return app.inference.submit(work).get(5, TimeUnit.MINUTES);
    }

    // Peak resident memory of this process so far, from the kernel's own accounting.
    private static String peakMemory() throws Exception {
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("/proc/self/status"))) {
            if (line.startsWith("VmHWM")) return line.replaceAll("\\s+", " ");
        }
        return "";
    }

    /** A harmless picture: sky, grass, a sun and a house, drawn with plain shapes. */
    static Bitmap harmlessPicture() {
        Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(120, 180, 240));
        paint.setColor(Color.rgb(70, 160, 70));
        canvas.drawRect(0, 320, 640, 480, paint);
        paint.setColor(Color.rgb(250, 220, 60));
        canvas.drawCircle(520, 90, 50, paint);
        paint.setColor(Color.rgb(170, 90, 60));
        canvas.drawRect(200, 220, 380, 360, paint);
        return bitmap;
    }

    private static List<float[]> circle(float cx, float cy, float r) {
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i <= 48; i++) {
            double angle = 2 * Math.PI * i / 48;
            points.add(new float[] {cx + r * (float) Math.cos(angle),
                    cy + r * (float) Math.sin(angle)});
        }
        return points;
    }

    private static List<float[]> path(float... corners) {
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i + 3 < corners.length; i += 2) {
            for (int step = 0; step < 16; step++) {
                float t = step / 16f;
                points.add(new float[] {corners[i] + (corners[i + 2] - corners[i]) * t,
                        corners[i + 1] + (corners[i + 3] - corners[i + 1]) * t});
            }
        }
        points.add(new float[] {corners[corners.length - 2], corners[corners.length - 1]});
        return points;
    }

    @Test
    public void eye_describesARealScreenshot() throws Exception {
        TestModels.ensureInstalled("eye");
        Bitmap screen = getInstrumentation().getUiAutomation().takeScreenshot()
                .copy(Bitmap.Config.ARGB_8888, false);
        long t0 = SystemClock.elapsedRealtime();
        ImageFindings findings = onInferenceThread(() -> app.eye.run(screen, defaults()));
        long coldMs = SystemClock.elapsedRealtime() - t0;
        long t1 = SystemClock.elapsedRealtime();
        onInferenceThread(() -> app.eye.run(screen, defaults()));
        StringBuilder tags = new StringBuilder();
        for (Tag tag : findings.tags) {
            tags.append(tag.name).append('=').append(Math.round(tag.confidence * 100)).append(' ');
        }
        Log.i(TAG, "eye coldMs=" + coldMs + " warmMs=" + (SystemClock.elapsedRealtime() - t1)
                + " kind=" + findings.kind + " aesthetic=" + findings.aesthetic + " people="
                + findings.people + " pets=" + findings.pets + " faces=" + findings.faces
                + " tags: " + tags + peakMemory());

        assertEquals("a capture of the screen is a screenshot", "screenshot", findings.kind);
        assertFalse("some tags are expected", findings.tags.isEmpty());
        for (Tag tag : findings.tags) {
            assertTrue(tag.name, tag.confidence >= 0 && tag.confidence <= 1);
        }
        for (int i = 1; i < findings.tags.size(); i++) {
            assertTrue("most confident first",
                    findings.tags.get(i - 1).confidence >= findings.tags.get(i).confidence);
        }
        assertEquals("nobody is in a screenshot of the home screen", 0, findings.people);
    }

    private static long residentKb() throws Exception {
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("/proc/self/status"))) {
            if (line.startsWith("VmRSS")) return Long.parseLong(line.replaceAll("\\D+", ""));
        }
        throw new AssertionError("no VmRSS line");
    }

    @Test
    public void eye_givesItsMemoryBackOnceItHasSatUnused() throws Exception {
        TestModels.ensureInstalled("eye");
        Bitmap picture = harmlessPicture();
        onInferenceThread(() -> app.eye.run(picture, defaults()));
        long loadedKb = residentKb();

        Thread.sleep((AppContainer.TOOL_IDLE_SECONDS + 10) * 1000L);
        onInferenceThread(() -> null); // lets a release that is under way finish
        long idleKb = residentKb();

        Log.i(TAG, "eye loadedKb=" + loadedKb + " idleKb=" + idleKb);
        assertTrue("memory held after the idle time: " + idleKb + " kB of " + loadedKb,
                idleKb < loadedKb - 500_000);
    }

    @Test
    public void moderator_passesAHarmlessPictureAndItsThresholdDecidesTheFlag()
            throws Exception {
        TestModels.ensureInstalled("moderator");
        Bitmap picture = harmlessPicture();

        Sensitivity usual = onInferenceThread(() -> app.moderator.run(picture, defaults()));
        Log.i(TAG, "moderator score=" + usual.score + " flagged=" + usual.flagged + " "
                + peakMemory());
        assertFalse("a drawing of a house must not be flagged", usual.flagged);
        assertTrue("score: " + usual.score, usual.score >= 0 && usual.score < 0.5);

        // The threshold is a real parameter of the model. At zero, any score counts.
        Map<String, Object> strict = new HashMap<>();
        strict.put(AppSettings.modelSettingKey("moderator", ModelRegistry.MODERATOR_THRESHOLD), 0);
        Sensitivity atZero = onInferenceThread(
                () -> app.moderator.run(picture, new AppSettings(1, strict)));
        assertTrue("at a threshold of zero everything is flagged", atZero.flagged);
    }

    @Test
    public void shapes_recognisesACircleALineAndARectangle() throws Exception {
        TestModels.ensureInstalled("shapes");
        long t0 = SystemClock.elapsedRealtime();
        RecognizedShape circle = onInferenceThread(
                () -> app.shapes.run(circle(300, 300, 120), defaults()));
        long coldMs = SystemClock.elapsedRealtime() - t0;
        RecognizedShape line = onInferenceThread(
                () -> app.shapes.run(path(100, 100, 500, 130), defaults()));
        RecognizedShape rectangle = onInferenceThread(() -> app.shapes.run(
                path(100, 100, 500, 100, 500, 350, 100, 350, 100, 100), defaults()));
        Log.i(TAG, "shapes coldMs=" + coldMs + " circle=" + circle.kind + " line=" + line.kind
                + " rectangle=" + rectangle.kind + " outline=" + rectangle.outline.size() + " "
                + peakMemory());

        assertEquals("ellipse", circle.kind);
        assertEquals("line", line.kind);
        assertEquals("rectangle", rectangle.kind);
        assertFalse("a cleaned-up outline comes back to draw", rectangle.outline.isEmpty());
        for (float[] point : rectangle.outline) {
            assertTrue("the outline stays where the stroke was drawn: " + point[0] + ","
                    + point[1], point[0] > 50 && point[0] < 550 && point[1] > 50 && point[1] < 400);
        }
    }

    @Test
    public void shapes_doesNotInventAShapeForAMess() throws Exception {
        TestModels.ensureInstalled("shapes");
        List<float[]> mess = new ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 80; i++) {
            mess.add(new float[] {100 + random.nextInt(400), 100 + random.nextInt(300)});
        }

        RecognizedShape result = onInferenceThread(() -> app.shapes.run(mess, defaults()));

        Log.i(TAG, "shapes mess=" + (result == null ? "null" : result.kind));
        assertTrue("a random scrawl is a scribble or nothing, never a clean shape",
                result == null || "scribble".equals(result.kind));
    }

    @Test
    public void shapes_needsMoreThanATap() throws Exception {
        TestModels.ensureInstalled("shapes");
        List<float[]> dot = new ArrayList<>();
        dot.add(new float[] {200, 200});

        RecognizedShape result = onInferenceThread(() -> app.shapes.run(dot, defaults()));

        assertTrue("a single point is not a shape",
                result == null || "scribble".equals(result.kind));
    }
}

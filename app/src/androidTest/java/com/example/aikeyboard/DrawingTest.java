// Drawing with the real Shapes model, by real touch gestures: the Drawing screen in the
// companion app, and the drawing panel in the keyboard, where a cleaned-up shape is inserted
// into an editor that accepts pictures and offered through the share sheet where it is not.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

@RunWith(AndroidJUnit4.class)
public class DrawingTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private final Context context = getInstrumentation().getTargetContext();
    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("shapes");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        for (String other : new String[] {"emo", "redact", "toxic", "tongue", "gist", "voz"}) {
            settings.setModelEnabled(other, false);
        }
        settings.setModelEnabled("shapes", true);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("shapes"), 5000));
        EditorTestActivity.lastContentMime = null;
        EditorTestActivity.lastContentUri = null;
    }

    @After
    public void tearDown() {
        settings.setModelEnabled("shapes", false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private UiObject2 ours(String id) {
        UiObject2 found = device.wait(Until.findObject(By.res(PKG, id)), TIMEOUT_MS);
        assertNotNull("not on screen: " + id, found);
        return found;
    }

    private boolean statusBecomes(String id, int text) {
        return device.wait(Until.hasObject(By.res(PKG, id).text(context.getString(text))),
                TIMEOUT_MS);
    }

    // Draws one stroke through the given points, each a fraction (0 to 1) of the surface.
    private void draw(String surfaceId, float... fractions) {
        Rect bounds = ours(surfaceId).getVisibleBounds();
        Point[] points = new Point[fractions.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Point(bounds.left + Math.round(bounds.width() * fractions[2 * i]),
                    bounds.top + Math.round(bounds.height() * fractions[2 * i + 1]));
        }
        device.swipe(points, 12);
        device.waitForIdle();
    }

    private void drawRectangle(String surfaceId) {
        draw(surfaceId, 0.2f, 0.25f, 0.8f, 0.25f, 0.8f, 0.75f, 0.2f, 0.75f, 0.2f, 0.25f);
    }

    private void focus(String fieldId) {
        ours(fieldId).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    // ---- The Drawing screen ----

    @Test
    public void theDrawingScreenRecognisesAStrokeAndClearStartsAgain() {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(MainActivity::openDrawing);
            assertTrue(statusBecomes("drawing_status", R.string.drawing_hint));

            drawRectangle("stroke_surface");
            assertTrue("status: " + ours("drawing_status").getText(),
                    statusBecomes("drawing_status", R.string.shape_rectangle));

            ours("drawing_clear").click();
            assertTrue(statusBecomes("drawing_status", R.string.drawing_hint));
        }
    }

    @Test
    public void aStrokeThatIsNoShapeSaysSoAndCanBeRetried() {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(MainActivity::openDrawing);
            // A tangle: back and forth across itself many times.
            draw("stroke_surface", 0.2f, 0.2f, 0.8f, 0.7f, 0.3f, 0.8f, 0.7f, 0.2f, 0.2f, 0.6f,
                    0.8f, 0.4f, 0.4f, 0.9f, 0.6f, 0.1f, 0.25f, 0.5f, 0.75f, 0.55f, 0.5f, 0.3f);
            assertTrue("status: " + ours("drawing_status").getText(),
                    statusBecomes("drawing_status", R.string.drawing_unclear));

            drawRectangle("stroke_surface");
            assertTrue(statusBecomes("drawing_status", R.string.shape_rectangle));
        }
    }

    @Test
    public void aRecognisedDrawingCanBeSharedThroughTheSystemSheet() {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(MainActivity::openDrawing);
            assertFalse("nothing to share before anything is drawn",
                    ours("drawing_share").isEnabled());
            drawRectangle("stroke_surface");
            assertTrue(statusBecomes("drawing_status", R.string.shape_rectangle));

            ours("drawing_share").click();

            assertTrue("the system share sheet did not open",
                    device.wait(Until.gone(By.res(PKG, "drawing_share")), 10_000));
            device.pressBack();
            assertTrue(device.wait(Until.hasObject(By.res(PKG, "drawing_share")), 10_000));
        }
    }

    // ---- The keyboard's drawing panel ----

    @Test
    public void theDrawingPanelStaysWithinTheKeyboardsOwnHeight() {
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            focus("field_plain");
            Rect toolRow = ours("tool_draw").getVisibleBounds();
            Rect keys = ours("keys").getVisibleBounds();

            ours("tool_draw").click();

            Rect surface = ours("draw_surface").getVisibleBounds();
            assertTrue("the panel grew above the keyboard: " + surface + " tool row " + toolRow,
                    surface.top >= toolRow.top);
            assertTrue(surface.bottom <= keys.bottom);
            assertTrue("the app being typed into must stay in view",
                    device.hasObject(By.res(PKG, "field_plain")));
        }
    }

    @Test
    public void theDrawToolIsOfferedOnlyWhereItCanRun() throws Exception {
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            focus("field_password");
            assertFalse(device.wait(Until.hasObject(By.res(PKG, "tool_draw")), 2000));

            focus("field_plain");
            ours("tool_draw");
            settings.setModelEnabled("shapes", false);
            assertTrue("with Shapes off there is nothing behind the button",
                    device.wait(Until.gone(By.res(PKG, "tool_draw")), TIMEOUT_MS));
        }
    }

    @Test
    public void aShapeDrawnInTheKeyboardIsInsertedIntoAnEditorThatTakesPictures()
            throws Exception {
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            focus("field_rich");
            ours("tool_draw").click();
            drawRectangle("draw_surface");
            assertTrue("status: " + ours("draw_status").getText(),
                    statusBecomes("draw_status", R.string.shape_rectangle));
            assertNull("nothing is sent until Insert is tapped",
                    EditorTestActivity.lastContentUri);

            ours("draw_insert").click();

            assertTrue("the editor never received the picture", TestModels.await(
                    () -> EditorTestActivity.lastContentUri != null, TIMEOUT_MS));
            assertEquals("image/png", EditorTestActivity.lastContentMime);
            // The editor was given a read grant with it: the picture really opens.
            try (InputStream in = context.getContentResolver()
                    .openInputStream(EditorTestActivity.lastContentUri)) {
                Bitmap received = BitmapFactory.decodeStream(in);
                assertNotNull("what arrived is not a picture", received);
                assertTrue(received.getWidth() > 100 && received.getHeight() > 100);
            }
            assertTrue("the panel closes and the keys come back",
                    device.wait(Until.gone(By.res(PKG, "draw_surface")), TIMEOUT_MS));
        }
    }

    @Test
    public void whereTheEditorTakesNoPicturesTheShapeIsOfferedThroughTheShareSheet() {
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            focus("field_plain");
            ours("tool_draw").click();
            drawRectangle("draw_surface");
            assertTrue(statusBecomes("draw_status", R.string.shape_rectangle));
            // The button says what will happen in this field.
            assertEquals(context.getString(R.string.draw_share), ours("draw_insert").getText());

            ours("draw_insert").click();

            assertTrue("the system share sheet did not open",
                    device.wait(Until.gone(By.res(PKG, "field_plain")), 10_000));
            assertNull("a plain text field must not be handed a picture",
                    EditorTestActivity.lastContentUri);
            device.pressBack();
            assertTrue(device.wait(Until.hasObject(By.res(PKG, "field_plain")), 10_000));
        }
    }

    @Test
    public void leavingTheDrawingPanelBringsTypingBackUntouched() {
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            focus("field_plain");
            ours("tool_draw").click();
            drawRectangle("draw_surface");

            ours("draw_back").click();

            assertTrue(device.wait(Until.gone(By.res(PKG, "draw_surface")), TIMEOUT_MS));
            device.wait(Until.findObject(By.pkg(PKG).desc("a")), TIMEOUT_MS).click();
            device.waitForIdle();
            String[] text = new String[1];
            host.onActivity(a -> text[0] = ((EditText) a.findViewById(R.id.field_plain))
                    .getText().toString());
            assertEquals("a", text[0]);
        }
    }
}

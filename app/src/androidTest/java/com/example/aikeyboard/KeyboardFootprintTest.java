// The keyboard keeps one footprint: its panels cover the keys at the keyboard's own height
// and never push the app being typed into up the screen. Models that do not fit in a panel
// are reached by scrolling inside it, with the way back always in view.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyboardFootprintTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        scenario = ActivityScenario.launch(EditorTestActivity.class);
        device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    @After
    public void tearDown() {
        scenario.close();
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private UiObject2 ours(String id) {
        UiObject2 found = device.wait(Until.findObject(By.res(PKG, id)), TIMEOUT_MS);
        assertNotNull("not on screen: " + id, found);
        return found;
    }

    @Test
    public void theAiPanelCoversTheKeyboardAndNoMore() {
        // The settings button fills the tool row, so its top is the keyboard's top.
        int keyboardTop = ours("tool_settings").getVisibleBounds().top;
        int keyboardBottom = ours("keys").getVisibleBounds().bottom;
        Rect fieldBefore = ours("field_plain").getVisibleBounds();

        ours("ai_toggle").click();

        Rect panel = ours("ai_panel").getVisibleBounds();
        assertTrue("the panel grew above the keyboard: " + panel + ", keyboard top was "
                + keyboardTop, panel.top >= keyboardTop);
        assertTrue("the panel should cover the keys", panel.bottom >= keyboardBottom);
        assertEquals("the app being typed into must not move",
                fieldBefore, ours("field_plain").getVisibleBounds());
    }

    @Test
    public void theAiPanelOpensAtTheTopEachTime() {
        ours("ai_toggle").click();
        ours("ai_panel_models").scrollUntil(Direction.DOWN,
                Until.hasObject(By.res(PKG, "ai_model_name").text("Gist")));
        ours("ai_panel_close").click();
        ours("keys");

        ours("ai_toggle").click();

        assertNotNull("reopened, the panel should start with its first model again",
                device.wait(Until.findObject(By.res(PKG, "ai_model_name").text("Emo")), 3000));
    }

    @Test
    public void everyTypingModelCanBeReachedAndTheWayBackStaysInView() {
        ours("ai_toggle").click();
        ours("ai_panel_master");

        // The last model in the list: found by scrolling inside the panel if it has to be.
        UiObject2 list = ours("ai_panel_models");
        if (!device.hasObject(By.res(PKG, "ai_model_name").text("Gist"))) {
            list.scrollUntil(Direction.DOWN,
                    Until.hasObject(By.res(PKG, "ai_model_name").text("Gist")));
        }
        assertTrue("Gist could not be reached in the panel",
                device.hasObject(By.res(PKG, "ai_model_name").text("Gist")));

        // Scrolled or not, the master switch and both ways out are still there.
        ours("ai_panel_master");
        ours("ai_panel_back");
        ours("ai_panel_close");
    }
}

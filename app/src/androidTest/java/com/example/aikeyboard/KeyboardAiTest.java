// AI in the keyboard, end to end with the real Emo model: a suggestion appears for the draft,
// a tap inserts it, and nothing runs where it must not (AI off, password field, blocked app,
// model not installed).
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class KeyboardAiTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    // A suggestion slot holding something that is not a word: an emoji.
    private static final BySelector EMOJI_SLOT =
            By.res(PKG, "suggestion_1").text(Pattern.compile("[^\\p{L}\\p{N}\\s]+"));

    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("emo");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of());
        settings.setModelEnabled("emo", true);
        setAi(true);
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() {
        scenario.close();
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of());
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void setAi(boolean on) throws InterruptedException {
        settings.setBoolean(AppSettings.AI_ENABLED, on);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled() == on, 5000));
    }

    private void focus(String fieldId) {
        device.wait(Until.findObject(By.res(PKG, fieldId)), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    private void tap(String... labels) {
        for (String label : labels) {
            UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
            assertNotNull("key not found: " + label, key);
            key.click();
        }
        device.waitForIdle();
    }

    private String text(int fieldId) {
        String[] result = new String[1];
        scenario.onActivity(a ->
                result[0] = ((EditText) a.findViewById(fieldId)).getText().toString());
        return result[0];
    }

    private void typePizza() {
        tap("p", "i", "z", "z", "a", "Space");
    }

    @Test
    public void anEmojiIsSuggestedForTheDraftAndATapInsertsIt() {
        focus("field_plain");
        typePizza();

        UiObject2 slot = device.wait(Until.findObject(EMOJI_SLOT), TIMEOUT_MS);
        assertNotNull("no emoji suggestion appeared", slot);
        String emoji = slot.getText();
        slot.click();
        device.waitForIdle();

        assertEquals("pizza " + emoji, text(R.id.field_plain));
    }

    @Test
    public void nothingIsSuggestedWhileAiIsOff() throws Exception {
        setAi(false);
        focus("field_plain");
        typePizza();
        assertNull("AI is off: no model may run", device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAPasswordField() {
        focus("field_password");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAFieldThatAsksNotToBeLearnedFrom() {
        focus("field_no_learning");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAnAppTheUserBlocked() throws Exception {
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of(PKG));
        assertTrue(TestModels.await(
                () -> settings.latest().stringSet(AppSettings.BLOCKED_APPS).contains(PKG), 5000));
        focus("field_plain");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void theAiPanelSwitchIsTheSameSettingTheCompanionAppUses() throws Exception {
        setAi(false);
        focus("field_plain");
        UiObject2 pill = device.wait(Until.findObject(By.res(PKG, "ai_toggle")), TIMEOUT_MS);
        assertNotNull("no AI control in the tool row", pill);
        assertTrue(pill.getContentDescription().contains("off"));

        pill.click();
        UiObject2 master = device.wait(
                Until.findObject(By.res(PKG, "ai_panel_master")), TIMEOUT_MS);
        assertNotNull("the AI panel did not open", master);
        master.click();

        assertTrue("the panel switch must write the shared setting",
                TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        device.findObject(By.res(PKG, "ai_panel_close")).click();
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "ai_toggle").descContains("on")),
                TIMEOUT_MS));
        assertFalse(device.hasObject(By.res(PKG, "ai_panel_master")));
    }

    @Test
    public void aModelSwitchedOnButNotInstalledOffersSetupInsteadOfRunning() throws Exception {
        TestModels.container().models.delete("emo");
        assertTrue(TestModels.await(
                () -> TestModels.container().models.bytesOnDisk("emo") == 0, 10_000));
        settings.setModelEnabled("emo", true);
        focus("field_plain");
        typePizza();

        assertNotNull("expected a setup hint", device.wait(Until.findObject(
                By.res(PKG, "suggestion_1").text("Set up AI models")), TIMEOUT_MS));
        assertEquals("typing must be unaffected", "pizza ", text(R.id.field_plain));
    }

    @Test
    public void aModelThatCrashedTheKeyboardIsSwitchedOffAtTheNextStart() throws Exception {
        AppContainer app = TestModels.container();
        app.breaker.loading("emo"); // as if the process had died inside the load

        app.disableModelsThatCrashed();

        assertTrue(TestModels.await(() -> !settings.latest().modelEnabled("emo"), 5000));
        assertTrue(settings.latest().bool(AppSettings.modelCrashedKey("emo"), false));
        // Turning it back on is the user's decision, and clears the warning.
        settings.setModelEnabled("emo", true);
        assertTrue(TestModels.await(
                () -> !settings.latest().bool(AppSettings.modelCrashedKey("emo"), true), 5000));
    }
}

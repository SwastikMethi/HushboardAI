// End-to-end probe of the keyboard service inside a real host field: typing through the real
// IME window, plus the platform questions the spike has to answer (bottom inset, a Material
// widget in a service, the emoji picker, microphone access while the keyboard is shown).
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.util.Log;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class ImeSpikeTest {

    private static final String TAG = "ImeSpike";
    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        device = UiDevice.getInstance(getInstrumentation());
        // A test run may start on a fresh install, so the test selects the keyboard itself.
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        // Emulators report a hardware keyboard, which otherwise hides the soft keyboard.
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        device.executeShellCommand("pm grant " + PKG + " android.permission.RECORD_AUDIO");
    }

    private UiObject2 key(String id) {
        UiObject2 key = device.wait(Until.findObject(By.res(PKG, id)), TIMEOUT_MS);
        assertNotNull("keyboard view not found: " + id, key);
        return key;
    }

    private static String fieldText(ActivityScenario<EditorTestActivity> scenario) {
        String[] text = new String[1];
        scenario.onActivity(a ->
                text[0] = ((EditText) a.findViewById(R.id.field_plain)).getText().toString());
        return text[0];
    }

    @Test
    public void keyboardTypesIntoARealFieldAndAnswersThePlatformProbes() throws Exception {
        try (ActivityScenario<EditorTestActivity> scenario =
                     ActivityScenario.launch(EditorTestActivity.class)) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();

            // Typing and deleting through the real IME window.
            key("key_a").click();
            key("key_b").click();
            key("key_space").click();
            device.waitForIdle();
            assertEquals("ab ", fieldText(scenario));
            key("key_backspace").click();
            device.waitForIdle();
            assertEquals("ab", fieldText(scenario));

            // Probe: a Material widget inflates inside the service's themed context.
            key("probe_switch");

            // Probe: the bottom row must sit fully on screen, above the navigation area.
            Rect keyBounds = key("key_a").getVisibleBounds();
            String inset = key("probe_inset").getText();
            Log.i(TAG, "display=" + device.getDisplayWidth() + "x" + device.getDisplayHeight()
                    + " key_a=" + keyBounds + " " + inset);
            assertTrue(keyBounds.bottom <= device.getDisplayHeight());

            // Probe: the emoji picker works inside the IME window.
            key("key_emoji").click();
            key("emoji_picker");
            // The picker labels each item "<emoji> emoji" for accessibility.
            UiObject2 emoji = device.wait(
                    Until.findObject(By.descStartsWith("😀")), TIMEOUT_MS);
            assertNotNull("no emoji item in the picker", emoji);
            emoji.click();
            device.waitForIdle();
            assertEquals("ab😀", fieldText(scenario));
            File shot = new File(ApplicationProvider.getApplicationContext()
                    .getExternalFilesDir(null), "ime_spike.png");
            device.takeScreenshot(shot);
            key("key_emoji").click();

            // Probe: microphone access from the service while the keyboard is on screen.
            key("key_mic").click();
            UiObject2 status = device.wait(
                    Until.findObject(By.res(PKG, "probe_status").textStartsWith("mic:")),
                    TIMEOUT_MS);
            assertNotNull("microphone probe did not report", status);
            Log.i(TAG, status.getText());
            assertTrue(status.getText(), status.getText().contains("silenced=false"));
        }
    }
}

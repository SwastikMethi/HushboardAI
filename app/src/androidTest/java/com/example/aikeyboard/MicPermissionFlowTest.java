// Opt-in: the microphone being refused, from both places that can ask for it. It has to
// start with the permission revoked, and revoking kills the app, so that is done from
// outside first:
//   adb shell pm revoke com.example.aikeyboard android.permission.RECORD_AUDIO
//   adb shell am instrument -w -e micDenied true -e class ...MicPermissionFlowTest ...
// Taps the system's own permission prompt, so it depends on that prompt's button ids.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class MicPermissionFlowTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    // The system prompt's "Don't allow" button: its id differs between the first refusal
    // and the one that makes the refusal permanent.
    private static final Pattern DENY = Pattern.compile(
            ".*:id/permission_deny(_and_dont_ask_again)?_button");

    private final Context context = ApplicationProvider.getApplicationContext();
    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        assumeTrue("opt-in: -e micDenied true", "true".equals(
                InstrumentationRegistry.getArguments().getString("micDenied")));
        assertEquals("revoke the microphone permission before this run (see the file header)",
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        TestModels.ensureInstalled("voz");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        settings.setModelEnabled("voz", true);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("voz"), 5000));
    }

    @After
    public void tearDown() {
        settings.setModelEnabled("voz", false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private UiObject2 ours(String id) {
        UiObject2 found = device.wait(Until.findObject(By.res(PKG, id)), TIMEOUT_MS);
        assertNotNull("not on screen: " + id, found);
        return found;
    }

    private void refuseInTheSystemPrompt() {
        UiObject2 deny = device.wait(Until.findObject(By.res(DENY)), TIMEOUT_MS);
        assertNotNull("the system's microphone prompt did not appear", deny);
        deny.click();
    }

    @Test
    public void aRefusalIsExplainedAndAPermanentOneLeadsToSettingsWhereAllowingWorks() {
        // 1. From the keyboard: the prompt appears at the tap; refusing leaves typing intact.
        try (ActivityScenario<EditorTestActivity> editor =
                ActivityScenario.launch(EditorTestActivity.class)) {
            ours("field_plain").click();
            ours("tool_mic").click();
            refuseInTheSystemPrompt();
            assertEquals(context.getString(R.string.mic_refused), ours("mic_message").getText());
            assertEquals("a first refusal can simply be asked again",
                    context.getString(R.string.mic_ask_again), ours("mic_action").getText());
            ours("mic_close").click();
            assertNotNull("the editor is still there after declining",
                    device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS));
        }

        // 2. From Voice tools: a second refusal is permanent, so the way back is Settings.
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(MainActivity::openVoiceTools);
            ours("voice_record").click();
            refuseInTheSystemPrompt();
            UiObject2 action = ours("mic_action");
            assertEquals(context.getString(R.string.mic_open_settings), action.getText());
            action.click();
            assertTrue("the app's page in Settings did not open",
                    device.wait(Until.hasObject(By.pkg("com.android.settings")), TIMEOUT_MS));

            // 3. Allowed there (done here through the test API), then back: recording starts.
            getInstrumentation().getUiAutomation().grantRuntimePermission(
                    PKG, Manifest.permission.RECORD_AUDIO);
            device.pressBack();
            assertNotNull("recording should start once access was allowed", device.wait(
                    Until.findObject(By.res(PKG, "voice_record")
                            .text(context.getString(R.string.voice_stop))), TIMEOUT_MS));
            ours("voice_cancel").click();
        }
    }
}

// Captures the screens the UI spec asks to be compared with the reference images: typing,
// AI suggestions, the AI panel, the catalog, a model detail and settings. Opt-in:
//   -e screenshots true      then pull /sdcard/Android/data/com.example.aikeyboard/files/tour
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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
import com.example.aikeyboard.models.ModelRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class ScreenshotTourTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;
    private File dir;

    @Before
    public void setUp() throws Exception {
        assumeTrue("opt-in: pass -e screenshots true", "true".equals(
                InstrumentationRegistry.getArguments().getString("screenshots")));
        TestModels.ensureInstalled("emo");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        dir = new File(ApplicationProvider.getApplicationContext().getExternalFilesDir(null),
                "tour");
        dir.mkdirs();
    }

    private void shot(String name) throws InterruptedException {
        device.waitForIdle();
        Thread.sleep(700); // let ripples and the enable sweep finish
        assertTrue(device.takeScreenshot(new File(dir, name + ".png")));
    }

    private void tap(String... labels) {
        for (String label : labels) {
            UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
            assertNotNull("key not found: " + label, key);
            key.click();
        }
    }

    private void setAi(boolean on) throws InterruptedException {
        settings.setBoolean(AppSettings.AI_ENABLED, on);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled() == on, 5000));
    }

    @Test
    public void keyboardStates() throws Exception {
        settings.setModelEnabled("emo", true);
        setAi(false);
        try (ActivityScenario<EditorTestActivity> host =
                     ActivityScenario.launch(EditorTestActivity.class)) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
            tap("Shift", "P", "i", "z", "z", "a", "Space", "s", "o", "u", "n", "d", "s",
                    "Space", "g", "o");
            shot("01_typing_ai_off");

            setAi(true);
            tap("o", "d");
            assertNotNull("no emoji suggestion", device.wait(Until.findObject(
                    By.res(PKG, "suggestion_1").text(Pattern.compile("[^\\p{L}\\p{N}\\s]+"))),
                    TIMEOUT_MS));
            shot("02_ai_suggestions");

            device.findObject(By.res(PKG, "ai_toggle")).click();
            assertNotNull(device.wait(
                    Until.findObject(By.res(PKG, "ai_panel_master")), TIMEOUT_MS));
            shot("03_ai_panel");
        } finally {
            setAi(false);
        }
    }

    @Test
    public void noticeStates() throws Exception {
        TestModels.ensureInstalled("redact");
        settings.setModelEnabled("redact", true);
        settings.setString(AppSettings.modelSettingKey("redact", ModelRegistry.REDACT_MODE),
                ModelRegistry.REDACT_MODE_MASK);
        setAi(true);
        try (ActivityScenario<EditorTestActivity> host =
                     ActivityScenario.launch(EditorTestActivity.class)) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
            String text = "Sure, mail me at anna@example.hu or call +36 30 123 4567 ";
            host.onActivity(a -> {
                android.widget.EditText field = a.findViewById(R.id.field_plain);
                field.setText(text);
                field.setSelection(text.length());
            });
            assertNotNull("no privacy notice", device.wait(Until.findObject(By.res(PKG,
                    "suggestion_1").text("Personal information detected")), TIMEOUT_MS));
            shot("08_privacy_notice");
            device.findObject(By.res(PKG, "suggestion_2")).click();
            assertNotNull(device.wait(
                    Until.findObject(By.res(PKG, "notice_detail")), TIMEOUT_MS));
            shot("09_review_panel");
        } finally {
            setAi(false);
        }
    }

    @Test
    public void voiceStates() throws Exception {
        assumeTrue("no speech synthesiser", TestSpeech.english() != null);
        for (String id : new String[] {"voz", "ear", "uhm", "who"}) {
            TestModels.ensureInstalled(id);
            settings.setModelEnabled(id, true);
        }
        getInstrumentation().getUiAutomation().grantRuntimePermission(
                PKG, android.Manifest.permission.RECORD_AUDIO);
        AppContainer container = TestModels.container();
        com.example.aikeyboard.media.AudioCaptureController real = container.capture;
        container.capture = new FakeCapture(TestSpeech.repeated(TestSpeech.english(), 3));
        setAi(true);
        try {
            try (ActivityScenario<EditorTestActivity> host =
                         ActivityScenario.launch(EditorTestActivity.class)) {
                device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
                device.wait(Until.findObject(By.res(PKG, "tool_mic")), TIMEOUT_MS).click();
                assertNotNull(device.wait(Until.findObject(
                        By.res(PKG, "suggestion_1").text("Listening…")), TIMEOUT_MS));
                shot("10_dictation_listening");
                device.findObject(By.res(PKG, "suggestion_2")).click();
                assertNotNull("no transcript", device.wait(
                        Until.findObject(By.res(PKG, "notice_detail")), 240_000));
                shot("11_dictation_transcript");
            }
            try (ActivityScenario<MainActivity> app =
                         ActivityScenario.launch(MainActivity.class)) {
                app.onActivity(MainActivity::openVoiceTools);
                device.wait(Until.findObject(By.res(PKG, "voice_record")), TIMEOUT_MS).click();
                shot("12_voice_tools_recording");
                device.findObject(By.res(PKG, "voice_record")).click();
                assertNotNull("no result", device.wait(
                        Until.findObject(By.res(PKG, "voice_transcript")), 240_000));
                shot("13_voice_tools_result");
            }
        } finally {
            container.capture = real;
            for (String id : new String[] {"voz", "ear", "uhm", "who"}) {
                settings.setModelEnabled(id, false);
            }
            setAi(false);
        }
    }

    @Test
    public void companionScreens() throws Exception {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            shot("04_keyboard_tab");
            device.findObject(By.res(PKG, "tab_models")).click();
            shot("05_models");
            device.findObject(By.text("Redact")).click();
            shot("06_model_detail");
            device.pressBack();
            device.findObject(By.res(PKG, "tab_settings")).click();
            shot("07_settings");
        }
    }
}

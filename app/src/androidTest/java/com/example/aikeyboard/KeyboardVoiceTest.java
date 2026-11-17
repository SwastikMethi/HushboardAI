// Dictation in the keyboard, end to end with the real Voz model: the microphone button
// appears only when transcription can really run, the transcript is previewed and inserted
// only on a tap, and a recording never outlives the keyboard being on screen. The microphone
// is replaced by known speech; the model, the keyboard and the editor are real.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.Voice.AudioClip;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyboardVoiceTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    // Loading a 1.2 GB model and transcribing takes a while on an emulator.
    private static final long TRANSCRIBE_MS = 4 * 60_000;

    private final AppContainer app = TestModels.container();
    private final SettingsRepository settings = app.settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;
    private AudioCaptureController realCapture;
    private FakeCapture microphone;

    @Before
    public void setUp() throws Exception {
        AudioClip speech = TestSpeech.english();
        assumeTrue("this device has no usable speech synthesiser", speech != null);
        TestModels.ensureInstalled("voz");
        getInstrumentation().getUiAutomation().grantRuntimePermission(
                PKG, Manifest.permission.RECORD_AUDIO);
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        for (String other : new String[] {"emo", "redact", "toxic", "tongue", "gist"}) {
            settings.setModelEnabled(other, false);
        }
        settings.setModelEnabled("voz", true);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("voz"), 5000));
        realCapture = app.capture;
        microphone = new FakeCapture(speech);
        app.capture = microphone;
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
        if (realCapture != null) app.capture = realCapture;
        settings.setModelEnabled("voz", false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void focus(String fieldId) {
        device.wait(Until.findObject(By.res(PKG, fieldId)), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    private UiObject2 mic() {
        return device.wait(Until.findObject(By.res(PKG, "tool_mic")), TIMEOUT_MS);
    }

    private UiObject2 slot(String id, String text) {
        return device.wait(Until.findObject(By.res(PKG, id).text(text)), TIMEOUT_MS);
    }

    private String text(int fieldId) {
        String[] result = new String[1];
        scenario.onActivity(a ->
                result[0] = ((EditText) a.findViewById(fieldId)).getText().toString());
        return result[0];
    }

    @Test
    public void theMicrophoneIsOfferedOnlyWhileTranscriptionCanReallyRun() throws Exception {
        focus("field_plain");
        assertNotNull("Voz is on and installed: the microphone should show", mic());

        settings.setModelEnabled("voz", false);
        assertTrue("with Voz off there is nothing behind the button",
                device.wait(Until.gone(By.res(PKG, "tool_mic")), TIMEOUT_MS));
    }

    @Test
    public void theMicrophoneIsNotOfferedInAPasswordField() {
        focus("field_password");
        assertFalse(device.wait(Until.hasObject(By.res(PKG, "tool_mic")), 2000));
    }

    @Test
    public void dictationIsPreviewedAndOnlyInsertedOnATap() {
        focus("field_plain");
        mic().click();
        assertNotNull("recording should be shown as it happens",
                slot("suggestion_1", "Listening…"));

        slot("suggestion_2", "Stop").click();
        UiObject2 transcript = device.wait(
                Until.findObject(By.res(PKG, "notice_detail")), TRANSCRIBE_MS);
        assertNotNull("no transcript preview appeared", transcript);
        String heard = transcript.getText();
        assertTrue("transcript: " + heard, TestSpeech.recognised(heard));
        assertEquals("nothing is typed until the user says so", "", text(R.id.field_plain));

        device.findObject(By.res(PKG, "notice_primary")).click();
        device.waitForIdle();

        assertEquals(heard, text(R.id.field_plain));
        assertFalse("the preview closes once it is used",
                device.hasObject(By.res(PKG, "notice_detail")));
    }

    @Test
    public void cancellingWhileListeningDiscardsTheRecording() {
        focus("field_plain");
        mic().click();
        assertNotNull(slot("suggestion_1", "Listening…"));

        slot("suggestion_3", "Cancel").click();

        assertTrue(device.wait(Until.gone(By.res(PKG, "suggestion_1").text("Listening…")),
                TIMEOUT_MS));
        assertTrue(microphone.cancelled);
        assertEquals("", text(R.id.field_plain));
    }

    @Test
    public void aRecordingStopsWhenTheKeyboardLeavesTheScreen() throws Exception {
        focus("field_plain");
        mic().click();
        assertNotNull(slot("suggestion_1", "Listening…"));

        device.pressBack(); // hides the keyboard

        assertTrue("the microphone must not stay open behind a hidden keyboard",
                TestModels.await(() -> microphone.cancelled, TIMEOUT_MS));
    }
}

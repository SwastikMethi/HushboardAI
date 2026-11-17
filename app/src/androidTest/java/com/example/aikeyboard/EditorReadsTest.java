// What the keyboard reads from the app it is typing into, seen from the app's side: the
// debug host's fields count every request for their text. A password field must never be
// read for AI, including when it is typed into with the on-screen keyboard hidden, and a
// selection far larger than the keyboard's window must not be handed to the models.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EditorReadsTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final String SHOW_WITH_HARD_KEYBOARD =
            "settings put secure show_ime_with_hard_keyboard ";
    private static final long TIMEOUT_MS = 8000;

    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("emo");
        TestModels.ensureInstalled("redact");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand(SHOW_WITH_HARD_KEYBOARD + "1");
        settings.setModelEnabled("emo", true);
        settings.setModelEnabled("redact", true);
        settings.setString(AppSettings.modelSettingKey("redact", ModelRegistry.REDACT_MODE),
                ModelRegistry.REDACT_MODE_WARN);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("redact"), 5000));
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() throws Exception {
        device.executeShellCommand(SHOW_WITH_HARD_KEYBOARD + "1");
        if (scenario != null) scenario.close();
        settings.setModelEnabled("emo", false);
        settings.setModelEnabled("redact", false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void tapKeys(String... labels) {
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

    @Test
    public void aPasswordTypedWithTheKeyboardHiddenIsNeverRead() throws Exception {
        // An ordinary field first, with the keyboard up and AI running in it.
        device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
        assertNotNull(device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
        tapKeys("h", "i");

        // Now a hardware keyboard takes over: the on-screen one stays away.
        device.executeShellCommand(SHOW_WITH_HARD_KEYBOARD + "0");
        device.findObject(By.res(PKG, "field_password")).click();
        assertTrue("the on-screen keyboard should have gone",
                device.wait(Until.gone(By.res(PKG, "keys")), TIMEOUT_MS));
        CountingEditText.resetCounts();

        for (int key : new int[] {KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_C,
                KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_T}) {
            device.pressKeyCode(key);
        }
        Thread.sleep(2000); // longer than any debounce the keyboard uses

        assertEquals("the hardware keys should have typed into the field", "secret",
                text(R.id.field_password));
        assertEquals("the keyboard asked a password field for its text", 0,
                CountingEditText.textReads.get());
    }

    @Test
    public void aPasswordFieldIsNotReadWithTheKeyboardShownEither() throws Exception {
        device.wait(Until.findObject(By.res(PKG, "field_password")), TIMEOUT_MS).click();
        assertNotNull(device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
        CountingEditText.resetCounts();

        tapKeys("p", "a", "s", "s");
        Thread.sleep(2000);

        assertEquals("pass", text(R.id.field_password));
        assertEquals("the keyboard asked a password field for its text", 0,
                CountingEditText.textReads.get());
    }

    @Test
    public void selectingAWholeLongNoteDoesNotHandItToTheModels() throws Exception {
        device.wait(Until.findObject(By.res(PKG, "field_multiline")), TIMEOUT_MS).click();
        assertNotNull(device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
        StringBuilder note = new StringBuilder();
        while (note.length() < 4000) note.append("The quick brown fox jumps over the dog. ");
        note.append("Write to anna@example.hu ");
        scenario.onActivity(a -> {
            EditText field = a.findViewById(R.id.field_multiline);
            field.setText(note);
            field.setSelection(note.length());
        });
        // With only the cursor at the end, the keyboard reads its bounded window as usual.
        assertNotNull("the address near the cursor should be noticed", device.wait(
                Until.findObject(By.res(PKG, "suggestion_1").text("Personal information detected")),
                TIMEOUT_MS));

        CountingEditText.resetCounts();
        scenario.onActivity(a -> ((EditText) a.findViewById(R.id.field_multiline)).selectAll());
        Thread.sleep(2000);

        assertEquals("a selection far larger than the keyboard's window was read as a draft",
                0, CountingEditText.draftReads.get());
        assertFalse("nothing may be reported about text that was not read", device.hasObject(
                By.res(PKG, "suggestion_1").text("Personal information detected")));
    }
}

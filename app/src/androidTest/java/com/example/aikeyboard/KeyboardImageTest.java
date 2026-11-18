// The keyboard's picture button and panel. The button is offered only while an image model
// can really run and the field allows AI. A picture chosen in the system's picker comes back
// to the keyboard, and what the image models found is shown there; the field is not touched.
// The system's picker cannot be driven by a test, so a picture is handed over the way the
// picker screen hands it over once it has read the chosen file.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.widget.EditText;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
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

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class KeyboardImageTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    private static final long RUN_MS = 60_000;

    private final AppContainer app = TestModels.container();
    private final SettingsRepository settings = app.settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("eye");
        TestModels.ensureInstalled("moderator");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        for (String other : new String[] {"emo", "redact", "toxic", "tongue", "gist"}) {
            settings.setModelEnabled(other, false);
        }
        use(true);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("moderator"), 5000));
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        use(false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void use(boolean on) throws InterruptedException {
        settings.setModelEnabled("eye", on);
        settings.setModelEnabled("moderator", on);
        assertTrue(TestModels.await(() -> settings.latest().modelEnabled("moderator") == on,
                5000));
    }

    private void focus(String fieldId) {
        device.wait(Until.findObject(By.res(PKG, fieldId)), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "tool_settings")), TIMEOUT_MS));
    }

    private String text(int fieldId) {
        String[] result = new String[1];
        scenario.onActivity(a ->
                result[0] = ((EditText) a.findViewById(fieldId)).getText().toString());
        return result[0];
    }

    // What the picker screen does with the picture the user chose, once it has read it.
    private void handOver() {
        Bitmap picture = VisionModelsTest.harmlessPicture();
        getInstrumentation().runOnMainSync(() -> app.picturePicked.accept(picture));
    }

    private String shown(String id, long timeoutMs) {
        UiObject2 view = device.wait(Until.findObject(By.res(PKG, id)), timeoutMs);
        return view == null ? null : view.getText();
    }

    @Test
    public void thePictureButtonIsOfferedOnlyWhileAnImageModelCanRun() throws Exception {
        focus("field_plain");
        assertNotNull("Eye and Moderator are on and installed: the button should show",
                device.wait(Until.findObject(By.res(PKG, "tool_image")), TIMEOUT_MS));

        use(false);
        assertTrue("with both image models off there is nothing behind the button",
                device.wait(Until.gone(By.res(PKG, "tool_image")), TIMEOUT_MS));
    }

    @Test
    public void thePictureButtonIsNotOfferedInAPasswordField() {
        focus("field_password");
        assertFalse(device.wait(Until.hasObject(By.res(PKG, "tool_image")), 2000));
    }

    @Test
    public void tappingTheButtonOpensThePickerScreenAndClosingItChangesNothing() {
        AtomicBoolean pickerScreenOpened = new AtomicBoolean();
        ActivityLifecycleCallback watch = (activity, stage) -> {
            if (activity.getClass().getSimpleName().equals("ImagePickActivity")) {
                pickerScreenOpened.set(true);
            }
        };
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(watch);
        try {
            focus("field_plain");
            device.wait(Until.findObject(By.res(PKG, "tool_image")), TIMEOUT_MS).click();
            assertTrue("the keyboard cannot show the system's picker; its own screen must",
                    TestModels.await(pickerScreenOpened::get, TIMEOUT_MS));

            device.pressBack(); // closes the system's picker without choosing
            assertFalse("nothing was chosen, so there is nothing to show",
                    device.wait(Until.hasObject(By.res(PKG, "picture_panel")), 3000));
            assertEquals("", text(R.id.field_plain));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(watch);
        }
    }

    @Test
    public void aChosenPictureIsDescribedAndCheckedInTheKeyboard() throws Exception {
        focus("field_plain");
        handOver();

        String kind = shown("picture_kind", RUN_MS);
        assertNotNull("Eye's description did not appear in the keyboard", kind);
        assertTrue(kind, kind.startsWith("Looks like"));
        String check = shown("picture_sensitivity", RUN_MS);
        assertNotNull("Moderator's check did not appear in the keyboard", check);
        assertTrue(check, check.contains("nothing flagged"));
        assertEquals("the draft is never touched", "", text(R.id.field_plain));

        device.findObject(By.res(PKG, "picture_back")).click();
        assertTrue(device.wait(Until.gone(By.res(PKG, "picture_panel")), TIMEOUT_MS));
        assertNotNull("the keys should be back",
                device.wait(Until.findObject(By.pkg(PKG).desc("q")), TIMEOUT_MS));
    }

    @Test
    public void aPictureChosenWhileTheKeyboardIsAwayIsShownWhenItReturns() throws Exception {
        focus("field_plain");
        // The picker covers the app, so the field loses the keyboard while the user chooses.
        scenario.moveToState(Lifecycle.State.STARTED);
        assertTrue("the keyboard should leave with the field",
                device.wait(Until.gone(By.res(PKG, "tool_settings")), TIMEOUT_MS));
        handOver();
        scenario.moveToState(Lifecycle.State.RESUMED);
        device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();

        String kind = shown("picture_kind", RUN_MS);
        assertNotNull("the picture chosen while away was not shown on return", kind);
        assertTrue(kind, kind.startsWith("Looks like"));
    }

    // The whole trip through the system's own picker. Only on request (-e picker true) and
    // only on an emulator with a picture in its gallery: the picker shows whatever pictures
    // the device holds, so this must never be pointed at somebody's phone.
    @Test
    public void theSystemsPickerHandsAChosenPictureBackToTheKeyboard() throws Exception {
        org.junit.Assume.assumeTrue("emulator only: pass -e picker true", "true".equals(
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("picker")));
        focus("field_plain");
        device.wait(Until.findObject(By.res(PKG, "tool_image")), TIMEOUT_MS).click();

        UiObject2 photo = device.wait(Until.findObject(By.descContains("taken on")), TIMEOUT_MS);
        assertNotNull("the picker offered no picture; put one in the emulator's gallery",
                photo);
        photo.click();
        // Some versions of the picker return at once; others wait for Done.
        UiObject2 done = device.wait(Until.findObject(By.text("Done")), 3000);
        if (done != null) done.click();

        // If Android does not bring the keyboard back by itself, the user taps the field.
        if (shown("picture_kind", 5000) == null) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
        }
        String kind = shown("picture_kind", RUN_MS);
        assertNotNull("the picture chosen in the picker was not shown in the keyboard", kind);
        assertTrue(kind, kind.startsWith("Looks like"));
        assertEquals("the draft is never touched", "", text(R.id.field_plain));
    }

    @Test
    public void movingToAnotherFieldClosesThePanelAndForgetsThePicture() throws Exception {
        focus("field_plain");
        handOver();
        assertNotNull(shown("picture_kind", RUN_MS));

        // The panel covers the keys, not the app: another field can still be tapped.
        device.findObject(By.res(PKG, "field_password")).click();
        assertTrue("a picture must not follow the user into another field",
                device.wait(Until.gone(By.res(PKG, "picture_panel")), TIMEOUT_MS));

        device.findObject(By.res(PKG, "field_plain")).click();
        assertNotNull(device.wait(Until.findObject(By.res(PKG, "tool_image")), TIMEOUT_MS));
        assertFalse("the picture was forgotten when its field was left",
                device.wait(Until.hasObject(By.res(PKG, "picture_panel")), 2000));
    }
}

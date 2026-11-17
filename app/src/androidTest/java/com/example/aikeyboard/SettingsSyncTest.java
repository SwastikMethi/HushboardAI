// One settings store for the whole app: every observer sees the same snapshot, and the
// keyboard follows a change made elsewhere without being reopened.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.rxjava3.disposables.Disposable;

@RunWith(AndroidJUnit4.class)
public class SettingsSyncTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private final SettingsRepository settings = TestModels.container().settings;

    private static AppSettings last(List<AppSettings> seen) {
        return seen.get(seen.size() - 1);
    }

    @Test
    public void quickWritesToOneSettingLandInTheOrderTheyWereMade() throws Exception {
        // The master AI switch is a privacy control: "off" must never be overtaken by an
        // "on" that was written a moment earlier.
        String key = "test.write_order";
        for (int round = 0; round < 40; round++) {
            for (int i = 0; i < 12; i++) settings.setInt(key, i);
            int last = 1000 + round;
            settings.setInt(key, last);
            assertTrue("the last write never arrived (round " + round + ")",
                    TestModels.await(() -> settings.latest().integer(key, -1) == last, 5000));
            Thread.sleep(40); // anything written earlier that is still on its way lands now
            assertEquals("an earlier write landed after a later one (round " + round + ")",
                    last, settings.latest().integer(key, -1));
        }
    }

    @Test
    public void everyObserverSeesTheSameSnapshotWithTheSameRevision() throws Exception {
        List<AppSettings> first = new CopyOnWriteArrayList<>();
        List<AppSettings> second = new CopyOnWriteArrayList<>();
        Disposable a = settings.observe().subscribe(first::add);
        Disposable b = settings.observe().subscribe(second::add);
        try {
            assertTrue(TestModels.await(() -> !first.isEmpty() && !second.isEmpty(), 5000));
            long revisionBefore = last(first).revision;
            boolean flipped = !last(first).bool("test.sync_flag", false);

            settings.setBoolean("test.sync_flag", flipped);

            assertTrue("observers did not hear about the change", TestModels.await(
                    () -> last(first).bool("test.sync_flag", !flipped) == flipped
                            && last(second).bool("test.sync_flag", !flipped) == flipped, 5000));
            assertSame("both must hold the very same snapshot", last(first), last(second));
            assertTrue(last(first).revision > revisionBefore);
            assertTrue(settings.latest().bool("test.sync_flag", !flipped) == flipped);
        } finally {
            a.dispose();
            b.dispose();
        }
    }

    @Test
    public void theKeyboardFollowsAHeightChangeWithoutBeingReopened() throws Exception {
        UiDevice device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        settings.setString(AppSettings.KEYBOARD_HEIGHT, AppSettings.HEIGHT_STANDARD);

        try (ActivityScenario<EditorTestActivity> scenario =
                     ActivityScenario.launch(EditorTestActivity.class)) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
            UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc("q")), TIMEOUT_MS);
            assertNotNull("keyboard did not appear", key);
            int standardHeight = key.getVisibleBounds().height();

            // The change comes from "elsewhere" (the companion app would write the same key).
            settings.setString(AppSettings.KEYBOARD_HEIGHT, AppSettings.HEIGHT_TALL);

            assertTrue("keys did not grow after the height setting changed",
                    TestModels.await(() -> {
                        UiObject2 now = device.findObject(By.pkg(PKG).desc("q"));
                        return now != null && now.getVisibleBounds().height() > standardHeight;
                    }, TIMEOUT_MS));
        } finally {
            settings.setString(AppSettings.KEYBOARD_HEIGHT, AppSettings.HEIGHT_STANDARD);
        }
    }
}

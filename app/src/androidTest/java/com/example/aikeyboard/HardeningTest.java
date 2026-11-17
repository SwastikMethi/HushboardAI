// Hardening checks that need a device but not a person: what the app sends over the network
// while its models work, whether anything typed is left on disk or in the log, and how
// downloading, deleting and resetting behave when they overlap or are asked for twice.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.TrafficStats;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceFragmentCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class HardeningTest {

    private static final String TAG = "Hardening";
    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    // A word no dictionary, model file or system log would contain by chance.
    private static final String SECRET = "zebraquartzvow";

    private final Context context = getInstrumentation().getTargetContext();
    private final AppContainer app = TestModels.container();
    private final SettingsRepository settings = app.settings;
    private final ModelRepository models = app.models;
    private final UiDevice device = UiDevice.getInstance(getInstrumentation());

    @After
    public void tearDown() {
        for (String id : new String[] {"emo", "redact", "toxic", "tongue", "gist", "shapes"}) {
            settings.setModelEnabled(id, false);
        }
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private static AppSettings defaults() {
        return new AppSettings(1, Map.of());
    }

    // ---- Network ----

    @Test
    public void runningInstalledModelsOnTextSendsAndReceivesNothing() throws Exception {
        for (String id : new String[] {"emo", "redact", "toxic", "gist"}) {
            TestModels.ensureInstalled(id);
        }
        String text = "Email me at anna@example.hu about the pizza party tonight";
        int uid = Process.myUid();

        // First, proof that the meter moves at all: one small request must show up on it.
        assumeTrue("needs network to prove the traffic meter works", TestModels.online());
        long meterBefore = TrafficStats.getUidTxBytes(uid);
        java.net.HttpURLConnection probe = (java.net.HttpURLConnection) new java.net.URL(
                ModelRegistry.find("shapes").baseUrl() + "shapes_meta.json").openConnection();
        probe.setRequestMethod("HEAD");
        probe.getResponseCode();
        probe.disconnect();
        assumeTrue("this device does not report per-app traffic", TestModels.await(
                () -> TrafficStats.getUidTxBytes(uid) > meterBefore, 10_000));

        // First use loads each model, which is when the vendor's license check happens.
        long loadSentBefore = TrafficStats.getUidTxBytes(uid);
        long loadReceivedBefore = TrafficStats.getUidRxBytes(uid);
        app.inference.submit(() -> {
            app.emo.run(text, defaults());
            app.redact.run(text, defaults());
            app.toxic.run(text, defaults());
            app.gist.run(text, defaults());
            app.tongue.run(text, defaults());
            return null;
        }).get(2, TimeUnit.MINUTES);
        Thread.sleep(5000); // let anything started by loading finish
        long sentBefore = TrafficStats.getUidTxBytes(uid);
        long receivedBefore = TrafficStats.getUidRxBytes(uid);
        // Reported, not asserted: it depends on whether the models were already loaded.
        Log.i(TAG, "loading the models: sentBytes=" + (sentBefore - loadSentBefore)
                + " receivedBytes=" + (receivedBefore - loadReceivedBefore));

        app.inference.submit(() -> {
            for (int i = 0; i < 25; i++) {
                String draft = text + " " + i;
                app.emo.run(draft, defaults());
                app.redact.run(draft, defaults());
                app.toxic.run(draft, defaults());
                app.gist.run(draft, defaults());
                app.tongue.run(draft, defaults());
            }
            return null;
        }).get(2, TimeUnit.MINUTES);
        Thread.sleep(5000); // anything sent in batches would have gone by now

        long sent = TrafficStats.getUidTxBytes(uid) - sentBefore;
        long received = TrafficStats.getUidRxBytes(uid) - receivedBefore;
        Log.i(TAG, "125 model runs: sentBytes=" + sent + " receivedBytes=" + received);
        assertEquals("the app sent data while its models ran on text", 0, sent);
        assertEquals("the app received data while its models ran on text", 0, received);
    }

    // ---- Nothing typed is kept ----

    private List<String> filesContaining(File root, byte[] needle) throws Exception {
        List<String> found = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) return found;
        for (File child : children) {
            if (child.isDirectory()) {
                // Model weights are large, read-only downloads; nothing typed goes there.
                if (!child.getName().equals("models")) found.addAll(filesContaining(child, needle));
            } else if (child.length() < 20_000_000) {
                byte[] bytes = Files.readAllBytes(child.toPath());
                if (new String(bytes, StandardCharsets.ISO_8859_1).contains(
                        new String(needle, StandardCharsets.ISO_8859_1))) {
                    found.add(child.getPath());
                }
            }
        }
        return found;
    }

    @Test
    public void whatIsTypedIsNotWrittenToTheAppsStorageOrToTheLog() throws Exception {
        for (String id : new String[] {"emo", "redact", "toxic"}) {
            TestModels.ensureInstalled(id);
            settings.setModelEnabled(id, true);
        }
        settings.setModelEnabled("tongue", true);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("toxic"), 5000));
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        device.executeShellCommand("logcat -c");

        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
            assertNotNull(device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
            for (char letter : (SECRET + " ").toCharArray()) {
                String name = letter == ' ' ? "Space" : String.valueOf(letter);
                UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(name)), TIMEOUT_MS);
                assertNotNull("key not found: " + name, key);
                key.click();
            }
            // Give every model its turn on the draft, and settings a moment to be saved.
            Thread.sleep(4000);
        }

        File dataDir = context.getFilesDir().getParentFile();
        List<String> files = filesContaining(dataDir, SECRET.getBytes(StandardCharsets.UTF_8));
        assertTrue("typed text was found in: " + files, files.isEmpty());
        String log = device.executeShellCommand("logcat -d");
        assertFalse("typed text reached the system log", log.contains(SECRET));
    }

    // ---- Memory pressure ----

    @Test
    public void modelsDroppedUnderMemoryPressureLoadAgainOnTheirNextUse() throws Exception {
        TestModels.ensureInstalled("emo");
        String text = "Pizza sounds good";
        assertFalse(app.inference.submit(() -> app.emo.run(text, defaults()))
                .get(1, TimeUnit.MINUTES).isEmpty());

        // What the app does when Android says memory is critically low.
        app.releaseAllModels();

        assertFalse("after being dropped, the model should simply load again",
                app.inference.submit(() -> app.emo.run(text, defaults()))
                        .get(1, TimeUnit.MINUTES).isEmpty());
    }

    // ---- Overlapping model operations ----

    @Test
    public void deletingAModelWaitsForARunInProgressAndLeavesNothingBehind() throws Exception {
        TestModels.ensureInstalled("eye");
        android.graphics.Bitmap picture = VisionModelsTest.harmlessPicture();

        Future<ImageFindings> run = app.inference.submit(() -> app.eye.run(picture, defaults()));
        models.delete("eye");

        // The run that had already been asked for finishes normally: its files were still
        // there, because deletion queues up behind it.
        assertNotNull(run.get(2, TimeUnit.MINUTES));
        assertTrue("the files should be gone once the run is over",
                TestModels.await(() -> models.bytesOnDisk("eye") == 0, 20_000));
        assertEquals(AssetState.NOT_INSTALLED, models.status("eye").state);
        assertFalse("deleting also switches the model off", settings.latest().modelEnabled("eye"));
    }

    @Test
    public void askingForADownloadTwiceStartsOneTransfer() throws Exception {
        assumeTrue("needs network", TestModels.online());
        models.delete("shapes");
        assertTrue(TestModels.await(() -> models.bytesOnDisk("shapes") == 0, 10_000));

        try (ActivityScenario<MainActivity> onScreen =
                ActivityScenario.launch(MainActivity.class)) {
            models.download("shapes");
            models.download("shapes");
            assertTrue(TestModels.awaitStatus("shapes", s -> s.state == AssetState.READY,
                    120_000));
        }

        String work = "download-shapes-" + ModelRegistry.find("shapes").revision;
        List<WorkInfo> jobs = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(work).get(5, TimeUnit.SECONDS);
        assertEquals("one model, one job: " + jobs, 1, jobs.size());
    }

    // ---- The model's own screen ----

    // Brings the row with this title on screen. A swipe would still be moving when the next
    // tap lands; asking the list itself is settled before the test goes on.
    private void scrollTo(ActivityScenario<MainActivity> screen, int title) {
        String wanted = context.getString(title);
        screen.onActivity(activity -> {
            PreferenceFragmentCompat fragment = (PreferenceFragmentCompat) activity
                    .getSupportFragmentManager().findFragmentById(R.id.screen);
            androidx.preference.Preference row = find(fragment.getPreferenceScreen(), wanted);
            assertNotNull("no row titled " + wanted, row);
            fragment.scrollToPreference(row);
        });
        getInstrumentation().waitForIdleSync();
        onView(withText(title)).check(matches(isDisplayed()));
    }

    private static androidx.preference.Preference find(
            androidx.preference.PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            androidx.preference.Preference row = group.getPreference(i);
            if (title.contentEquals(String.valueOf(row.getTitle()))) return row;
            if (row instanceof androidx.preference.PreferenceGroup) {
                androidx.preference.Preference inner =
                        find((androidx.preference.PreferenceGroup) row, title);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    @Test
    public void deletingFromAModelsScreenAsksFirstAndCancelKeepsIt() throws Exception {
        TestModels.ensureInstalled("shapes");
        try (ActivityScenario<MainActivity> screen =
                ActivityScenario.launch(MainActivity.class)) {
            // The way a user gets here: the Models tab, then the model's row.
            screen.onActivity(activity -> activity.selectTab(R.id.tab_models));
            getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> activity.openModelDetail("shapes"));
            scrollTo(screen, R.string.detail_delete);

            onView(withText(R.string.detail_delete)).perform(click());
            onView(withText(R.string.action_cancel)).inRoot(isDialog()).perform(click());
            assertTrue("cancelling must keep the model", models.isReady("shapes"));

            onView(withText(R.string.detail_delete)).perform(click());
            onView(withText(R.string.action_delete)).inRoot(isDialog()).perform(click());
            assertTrue(TestModels.await(() -> !models.isReady("shapes")
                    && models.bytesOnDisk("shapes") == 0, 20_000));
            // Back on the catalog, where the model can be downloaded again.
            onView(withId(R.id.models_heading)).check(matches(isDisplayed()));
        }
        TestModels.ensureInstalled("shapes");
    }

    @Test
    public void resettingOneModelsSettingsLeavesTheOthersAlone() throws Exception {
        String redactConfidence = AppSettings.modelSettingKey("redact",
                ModelRegistry.REDACT_MIN_CONFIDENCE);
        String emoLimit = AppSettings.modelSettingKey("emo", ModelRegistry.EMO_LIMIT);
        settings.setInt(redactConfidence, 90);
        settings.setInt(emoLimit, 1);
        assertTrue(TestModels.await(() -> settings.latest().integer(emoLimit, -1) == 1
                && settings.latest().integer(redactConfidence, -1) == 90, 5000));
        try (ActivityScenario<MainActivity> screen =
                ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(activity -> activity.openModelDetail("redact"));
            scrollTo(screen, R.string.detail_reset);

            onView(withText(R.string.detail_reset)).perform(click());

            assertTrue("Redact's own setting should be back at its default", TestModels.await(
                    () -> settings.latest().integer(redactConfidence, -1) != 90, 5000));
            assertEquals("another model's setting must not be touched", 1,
                    settings.latest().integer(emoLimit, -1));
        } finally {
            settings.setInt(emoLimit, 3);
        }
    }
}

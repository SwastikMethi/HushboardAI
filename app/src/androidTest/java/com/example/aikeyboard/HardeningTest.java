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

        assumeTrue("needs network to prove the traffic meter works", TestModels.online());

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
        assumeTheMeterMoves(uid);
    }

    // A zero only means something if the meter can show a non-zero: one small request must
    // register. Done after the measurement, so its own connection cannot leak into it; if
    // the device does not meter per-app traffic the test is skipped, not passed.
    private static void assumeTheMeterMoves(int uid) throws Exception {
        long before = TrafficStats.getUidTxBytes(uid);
        java.net.HttpURLConnection probe = (java.net.HttpURLConnection) new java.net.URL(
                ModelRegistry.find("shapes").baseUrl() + "shapes_meta.json").openConnection();
        probe.setRequestMethod("HEAD");
        probe.setRequestProperty("Connection", "close"); // or its closing lands in a later test
        probe.getResponseCode();
        probe.disconnect();
        assumeTrue("this device does not report per-app traffic", TestModels.await(
                () -> TrafficStats.getUidTxBytes(uid) > before, 10_000));
    }

    // A connection kept open for reuse is closed by the server a minute or so after the
    // download, and those closing packets are then counted against the app while someone is
    // typing. Seen as 80 bytes in the measurement above when it ran after a download.
    @Test
    public void aFinishedDownloadLeavesNoConnectionOpen() throws Exception {
        assumeTrue("needs network", TestModels.online());
        com.example.aikeyboard.models.ModelDescriptor shapes = ModelRegistry.find("shapes");
        List<com.example.aikeyboard.models.ModelDescriptor.ModelFile> smallest = List.of(
                java.util.Collections.min(shapes.files,
                        java.util.Comparator.comparingLong(file -> file.bytes)));
        File dir = new File(context.getCacheDir(), "connection-check");
        File fetched = new File(dir, smallest.get(0).path);
        fetched.delete(); // a file already there would not be fetched at all
        int before = openSockets();
        assumeTrue("cannot list this process's open files", before >= 0);
        try {
            new com.example.aikeyboard.models.ModelDownloader().fetch(shapes.baseUrl(), smallest,
                    dir, new java.util.concurrent.atomic.AtomicBoolean(), (done, total) -> { });

            assertTrue("the file should have arrived", fetched.exists());
            assertTrue("a connection to the download server is still open: " + before
                            + " sockets before, " + openSockets() + " after",
                    TestModels.await(() -> openSockets() <= before, 5000));
        } finally {
            fetched.delete();
            dir.delete();
        }
    }

    private static int openSockets() {
        File[] descriptors = new File("/proc/self/fd").listFiles();
        if (descriptors == null) return -1;
        int count = 0;
        for (File descriptor : descriptors) {
            try {
                if (android.system.Os.readlink(descriptor.getPath()).startsWith("socket:")) {
                    count++;
                }
            } catch (android.system.ErrnoException closedMeanwhile) {
                // Gone between listing and reading: not open.
            }
        }
        return count;
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

    // ---- The crash breaker must not misfire ----

    @Test
    public void aModelLoadedTwiceInOneProcessLeavesNoCrashMark() throws Exception {
        TestModels.ensureInstalled("shapes");
        java.util.List<float[]> line = new ArrayList<>();
        for (int i = 0; i < 20; i++) line.add(new float[] {100 + i * 20, 100 + i});
        File mark = new File(context.getNoBackupFilesDir(), "loading/shapes");

        app.inference.submit(() -> app.shapes.run(line, defaults())).get(1, TimeUnit.MINUTES);
        // What the idle timer does: unload it. The next use loads it a second time.
        app.inference.submit(() -> app.runtime.release("shapes")).get(1, TimeUnit.MINUTES);
        app.inference.submit(() -> app.shapes.run(line, defaults())).get(1, TimeUnit.MINUTES);

        assertFalse("a second, successful load left a crash mark: the model would be "
                + "switched off at the next start", mark.exists());
    }

    // ---- Downloads must not hold up everything else ----

    @Test
    public void twoDownloadsInProgressDoNotHoldUpOtherFileWork() throws Exception {
        assumeTrue("needs network", TestModels.online());
        for (String id : new String[] {"toxic", "gist"}) models.delete(id);
        assertTrue(TestModels.await(() -> models.bytesOnDisk("toxic") == 0
                && models.bytesOnDisk("gist") == 0, 20_000));

        try (ActivityScenario<MainActivity> onScreen =
                ActivityScenario.launch(MainActivity.class)) {
            models.download("toxic");
            models.download("gist");
            assertTrue("both downloads should be running", TestModels.await(
                    () -> models.status("toxic").state == AssetState.DOWNLOADING
                            && models.status("gist").state == AssetState.DOWNLOADING, 30_000));

            // Opening a picture, sharing or deleting all use this executor.
            Future<?> otherWork = app.io.submit(() -> { });
            try {
                otherWork.get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("other file work waited behind two downloads");
            }
        } finally {
            TestModels.ensureInstalled("toxic");
            TestModels.ensureInstalled("gist");
        }
    }

    // ---- Network, for the models that take recordings, pictures and strokes ----

    @Test
    public void runningTheVoiceImageAndDrawingModelsSendsAndReceivesNothing() throws Exception {
        com.example.aikeyboard.media.Voice.AudioClip speech = TestSpeech.english();
        assumeTrue("this device has no usable speech synthesiser", speech != null);
        assumeTrue("needs network to prove the traffic meter works", TestModels.online());
        boolean withClear = "true".equals(
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("clear"));
        for (String id : new String[] {"voz", "ear", "uhm", "who", "eye", "moderator",
                "shapes"}) {
            TestModels.ensureInstalled(id);
        }
        if (withClear) TestModels.ensureInstalled("clear");
        com.example.aikeyboard.media.Voice.AudioClip longer = TestSpeech.repeated(speech, 3);
        android.graphics.Bitmap picture = VisionModelsTest.harmlessPicture();
        java.util.List<float[]> stroke = new ArrayList<>();
        for (int i = 0; i < 30; i++) stroke.add(new float[] {100 + i * 10, 200});
        Map<String, Object> chosen = new java.util.HashMap<>();
        chosen.put(AppSettings.AI_ENABLED, true);
        for (String id : new String[] {"voz", "ear", "uhm", "who"}) {
            chosen.put(AppSettings.modelEnabledKey(id), true);
        }
        if (withClear) chosen.put(AppSettings.modelEnabledKey("clear"), true);
        AppSettings voiceOn = new AppSettings(1, chosen);
        java.util.concurrent.Callable<Void> everything = () -> {
            app.voice.run(longer, voiceOn, id -> voiceOn.modelActive(id)
                    && models.isReady(id), new java.util.concurrent.atomic.AtomicBoolean(),
                    id -> { });
            app.eye.run(picture, defaults());
            app.moderator.run(picture, defaults());
            app.shapes.run(stroke, defaults());
            return null;
        };
        int uid = Process.myUid();

        // First use loads each model (and is when a license check would happen).
        long loadSentBefore = TrafficStats.getUidTxBytes(uid);
        long loadReceivedBefore = TrafficStats.getUidRxBytes(uid);
        app.inference.submit(everything).get(10, TimeUnit.MINUTES);
        Thread.sleep(5000);
        long sentBefore = TrafficStats.getUidTxBytes(uid);
        long receivedBefore = TrafficStats.getUidRxBytes(uid);
        // Reported, not asserted: it depends on which models this device has loaded before.
        Log.i(TAG, "loading the voice, image and drawing models: sentBytes="
                + (sentBefore - loadSentBefore) + " receivedBytes="
                + (receivedBefore - loadReceivedBefore));

        for (int i = 0; i < 3; i++) app.inference.submit(everything).get(10, TimeUnit.MINUTES);
        Thread.sleep(5000);

        long sent = TrafficStats.getUidTxBytes(uid) - sentBefore;
        long received = TrafficStats.getUidRxBytes(uid) - receivedBefore;
        Log.i(TAG, "voice, image and drawing runs (clear=" + withClear + "): sentBytes=" + sent
                + " receivedBytes=" + received);
        assertEquals("the app sent data while these models ran", 0, sent);
        assertEquals("the app received data while these models ran", 0, received);
        assumeTheMeterMoves(uid);
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
        // Loaded first, so the run below has the model in hand whatever the inference thread
        // was still finishing. A run that has not reached the model when Delete is asked for
        // is turned away as "not installed" instead, which is the other half of the rule.
        app.inference.submit(() -> app.eye.run(picture, defaults())).get(2, TimeUnit.MINUTES);

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

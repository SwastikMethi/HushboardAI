// The Voice tools screen with real models: how it is reached, which steps it says it will
// run, importing and recording, the preview, copying, cancelling and refusing a bad file.
// The microphone is replaced by known speech; everything else is the real thing.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.ClipboardManager;
import android.net.Uri;
import android.widget.TextView;

import androidx.preference.PreferenceFragmentCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.ui.tools.VoiceToolsFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class VoiceToolsScreenTest {

    private static final long RUN_MS = 4 * 60_000;

    private final AppContainer container = TestModels.container();
    private final SettingsRepository settings = container.settings;
    private ActivityScenario<MainActivity> app;
    private AudioCaptureController realCapture;
    private FakeCapture microphone;

    @Before
    public void setUp() throws Exception {
        assumeTrue("this device has no usable speech synthesiser",
                TestSpeech.english() != null);
        TestModels.ensureInstalled("voz");
        TestModels.ensureInstalled("ear");
        getInstrumentation().getUiAutomation().grantRuntimePermission(
                "com.example.aikeyboard", Manifest.permission.RECORD_AUDIO);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        use(true, "voz", "ear");
        use(false, "clear", "uhm", "who");
        realCapture = container.capture;
        microphone = new FakeCapture(TestSpeech.english());
        container.capture = microphone;
        app = ActivityScenario.launch(MainActivity.class);
        // Opened directly; the route a user takes has its own test below.
        app.onActivity(MainActivity::openVoiceTools);
        getInstrumentation().waitForIdleSync();
    }

    @Test
    public void theModelsTabLeadsToVoiceTools() {
        pressBack();
        closeSoftKeyboard(); // the practice field on the first tab may have raised it
        onView(withId(R.id.tab_models)).perform(click());
        onView(withText(R.string.open_voice_tools)).perform(scrollTo(), click());

        onView(withId(R.id.voice_steps)).check(matches(isDisplayed()));
    }

    @After
    public void tearDown() {
        if (app != null) app.close();
        if (realCapture != null) container.capture = realCapture;
        for (String id : new String[] {"voz", "ear"}) settings.setModelEnabled(id, false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void use(boolean on, String... modelIds) throws InterruptedException {
        for (String id : modelIds) settings.setModelEnabled(id, on);
        String last = modelIds[modelIds.length - 1];
        assertTrue(TestModels.await(() -> settings.latest().modelEnabled(last) == on, 5000));
        getInstrumentation().waitForIdleSync();
    }

    // The text a view is showing right now, or "" while it is hidden.
    private String shown(int viewId) {
        String[] text = {""};
        app.onActivity(activity -> {
            TextView view = activity.findViewById(viewId);
            if (view != null && view.isShown()) text[0] = view.getText().toString();
        });
        return text[0];
    }

    private void importFile(File file) {
        app.onActivity(activity -> ((VoiceToolsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.screen)).importAudio(Uri.fromFile(file)));
    }

    private void awaitTranscript() throws InterruptedException {
        assertTrue("no transcript appeared; status: " + shown(R.id.voice_status),
                TestModels.await(() -> TestSpeech.recognised(shown(R.id.voice_transcript)),
                        RUN_MS));
    }

    @Test
    public void theScreenSaysWhichStepsWillRun() {
        String steps = shown(R.id.voice_steps);
        assertTrue(steps, steps.contains("Voz") && steps.contains("Ear"));
        assertFalse("Uhm is off, so it must not be listed: " + steps, steps.contains("Uhm"));
        onView(withId(R.id.voice_record)).check(matches(isEnabled()));
        onView(withId(R.id.voice_import)).check(matches(isEnabled()));
    }

    @Test
    public void withNoVoiceModelOnThereIsNothingToRunAndTheScreenSaysSo() throws Exception {
        use(false, "voz", "ear");
        onView(withId(R.id.voice_steps)).check(matches(withText(R.string.voice_no_steps)));
        onView(withId(R.id.voice_record)).check(matches(not(isEnabled())));
        onView(withId(R.id.voice_import)).check(matches(not(isEnabled())));
    }

    @Test
    public void anImportedRecordingIsTranscribedPreviewedAndCanBeCopied() throws Exception {
        importFile(TestSpeech.wav());
        awaitTranscript();

        assertTrue(shown(R.id.voice_language), shown(R.id.voice_language).contains("English"));
        onView(withId(R.id.voice_copy)).perform(scrollTo(), click());
        String[] copied = new String[1];
        app.onActivity(activity -> copied[0] = String.valueOf(activity.getSystemService(
                ClipboardManager.class).getPrimaryClip().getItemAt(0).getText()));
        assertTrue("clipboard: " + copied[0], TestSpeech.recognised(copied[0]));
        // Android shows its own preview of what was copied, with share buttons, over the
        // bottom of the screen for a few seconds. Left there, a later test's tap on a tab
        // lands on it (and opens Quick Share), so this test waits for it to go.
        androidx.test.uiautomator.UiDevice device =
                androidx.test.uiautomator.UiDevice.getInstance(getInstrumentation());
        androidx.test.uiautomator.BySelector preview =
                androidx.test.uiautomator.By.res("com.android.systemui", "clipboard_ui");
        device.wait(androidx.test.uiautomator.Until.hasObject(preview), 2000);
        assertTrue("the system's clipboard preview is still on screen",
                device.wait(androidx.test.uiautomator.Until.gone(preview), 15_000));
    }

    @Test
    public void withClearOnTheCleanedAudioCanBeSharedThroughTheSystemSheet() throws Exception {
        // Opt-in: Clear needs a real GPU (see VoiceModelsTest).
        assumeTrue("true".equals(
                androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("clear")));
        TestModels.ensureInstalled("clear");
        use(true, "clear");
        try {
            importFile(TestSpeech.wav());
            awaitTranscript();
            assertTrue(shown(R.id.voice_notes), shown(R.id.voice_notes).contains("cleaned"));

            // The cleaned audio can be listened to before it goes anywhere.
            onView(withId(R.id.voice_play)).perform(scrollTo(), click());
            onView(withId(R.id.voice_play)).check(matches(withText(R.string.voice_stop_playing)));
            onView(withId(R.id.voice_play)).perform(click());
            onView(withId(R.id.voice_play)).check(matches(withText(R.string.voice_play)));

            onView(withId(R.id.voice_share)).perform(scrollTo(), click());

            androidx.test.uiautomator.UiDevice device =
                    androidx.test.uiautomator.UiDevice.getInstance(getInstrumentation());
            assertTrue("the system share sheet did not open", device.wait(
                    androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.res(
                            "com.example.aikeyboard", "voice_share")), 10_000));
            device.pressBack();
            // Wait for the sheet to be gone, or the next test's taps would land on it.
            assertTrue("the share sheet did not close", device.wait(
                    androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.res(
                            "com.example.aikeyboard", "voice_share")), 10_000));
        } finally {
            use(false, "clear");
        }
    }

    @Test
    public void recordThenStopRunsTheStepsOnWhatWasRecorded() throws Exception {
        onView(withId(R.id.voice_record)).perform(click());
        onView(withId(R.id.voice_record)).check(matches(withText(R.string.voice_stop)));

        onView(withId(R.id.voice_record)).perform(click());

        awaitTranscript();
        onView(withId(R.id.voice_record)).check(matches(withText(R.string.voice_record)));
    }

    @Test
    public void aFileThatIsNotAudioIsRefusedWithAReason() throws Exception {
        File text = new File(getInstrumentation().getTargetContext().getCacheDir(), "notes.txt");
        Files.write(text.toPath(), "not a recording".getBytes(StandardCharsets.UTF_8));

        importFile(text);

        assertTrue("status: " + shown(R.id.voice_status), TestModels.await(
                () -> shown(R.id.voice_status).contains("audio"), 20_000));
        assertEquals("nothing was run, so there is nothing to preview", "",
                shown(R.id.voice_transcript));
    }

    @Test
    public void cancellingARunDropsItsResult() throws Exception {
        importFile(TestSpeech.wav());
        assertTrue(TestModels.await(() -> !shown(R.id.voice_cancel).isEmpty(), 10_000));

        onView(withId(R.id.voice_cancel)).perform(click());

        onView(withId(R.id.voice_status)).check(matches(withText(R.string.voice_cancelled)));
        // Let whatever was already running finish; its result must not appear afterwards.
        container.inference.submit(() -> { }).get(RUN_MS, TimeUnit.MILLISECONDS);
        getInstrumentation().waitForIdleSync();
        assertEquals("", shown(R.id.voice_transcript));
    }

    @Test
    public void leavingTheScreenStopsARecording() throws Exception {
        onView(withId(R.id.voice_record)).perform(click());
        onView(withId(R.id.voice_record)).check(matches(withText(R.string.voice_stop)));

        pressBack();

        assertTrue("the microphone must not stay open after the screen is left",
                TestModels.await(() -> microphone.cancelled, 5000));
    }

    @Test
    public void aVoiceModelsOwnScreenLeadsHereToTryIt() {
        pressBack();
        app.onActivity(activity -> activity.openModelDetail("voz"));
        app.onActivity(activity -> ((PreferenceFragmentCompat) activity
                .getSupportFragmentManager().findFragmentById(R.id.screen))
                .scrollToPreference("try_it"));
        getInstrumentation().waitForIdleSync();

        onView(withText(R.string.open_voice_tools)).perform(click());

        onView(withId(R.id.voice_steps)).check(matches(isDisplayed()));
    }
}

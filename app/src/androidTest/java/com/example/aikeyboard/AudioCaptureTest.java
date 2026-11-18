// Recording from the device's real microphone: format, the time limit, cancelling, and the
// permission screen when access is already granted. On an emulator the microphone delivers
// silence, which is enough to check everything except what was said.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.AudioPipeline;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.ui.tools.MicPermissionActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class AudioCaptureTest {

    private final Context context = ApplicationProvider.getApplicationContext();
    private AudioCaptureController capture;

    @Before
    public void setUp() {
        TestModels.allowMicrophone();
        capture = new AudioCaptureController(context);
    }

    @After
    public void tearDown() {
        if (capture != null) capture.cancel(); // setUp may not have got that far
    }

    @Test
    public void recordsMonoSpeechRateAudioForAsLongAsItRuns() throws Exception {
        assertTrue("the microphone should open", capture.start(30, () -> { }));
        assertTrue(capture.isRecording());
        Thread.sleep(1500);

        AudioClip clip = capture.stop();

        assertFalse(capture.isRecording());
        assertEquals(AudioPipeline.SPEECH_RATE, clip.sampleRate);
        assertTrue("seconds: " + clip.seconds(), clip.seconds() > 1.0 && clip.seconds() < 3.0);
    }

    @Test
    public void stopsItselfAtTheLimitAndSaysSo() throws Exception {
        CountDownLatch limit = new CountDownLatch(1);
        assertTrue(capture.start(1, limit::countDown));

        assertTrue("the limit was never reported", limit.await(6, TimeUnit.SECONDS));
        AudioClip clip = capture.stop();

        assertEquals("exactly the limit is kept", 1.0, clip.seconds(), 0.01);
    }

    @Test
    public void cancellingThrowsTheAudioAway() throws Exception {
        assertTrue(capture.start(30, () -> { }));
        Thread.sleep(300);

        capture.cancel();

        assertFalse(capture.isRecording());
        assertNull("nothing is left to take after a cancel", capture.stop());
    }

    @Test
    public void aSecondRecordingCannotStartWhileOneIsRunning() {
        assertTrue(capture.start(30, () -> { }));
        assertFalse(capture.start(30, () -> { }));
    }

    @Test
    public void thePermissionScreenStepsAsideWhenAccessIsAlreadyGranted() {
        try (ActivityScenario<MicPermissionActivity> screen =
                ActivityScenario.launchActivityForResult(MicPermissionActivity.class)) {
            assertEquals(Activity.RESULT_OK, screen.getResult().getResultCode());
        }
    }

    // The keyboard opens these while another app is in front. A screen that shared this
    // app's task would bring the app's own screens forward with it, and closing it would
    // leave the user there instead of where they were typing: seen with the microphone
    // screen opened from another app's field while this app was open in the background.
    @Test
    public void theScreensTheKeyboardOpensHaveATaskOfTheirOwn() throws Exception {
        for (Class<?> screen : new Class<?>[] {MicPermissionActivity.class,
                com.example.aikeyboard.ui.tools.ImagePickActivity.class}) {
            android.content.pm.ActivityInfo declared = context.getPackageManager()
                    .getActivityInfo(new android.content.ComponentName(context, screen), 0);
            assertNull(screen.getSimpleName() + " would join the app's own task",
                    declared.taskAffinity);
        }
    }
}

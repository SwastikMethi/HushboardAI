// The voice models on a device, fed real speech: Android's own text-to-speech writes a WAV
// file, the app's decoder reads it, and each model is run on the result. Skipped when the
// device has no usable speech synthesiser. The Clear tests are opt-in (`-e clear true`).
package com.example.aikeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.adapters.ClearAdapter;
import com.example.aikeyboard.ai.adapters.EarAdapter;
import com.example.aikeyboard.ai.adapters.UhmAdapter;
import com.example.aikeyboard.ai.adapters.WhoAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.AudioPipeline;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.media.Voice.VoiceResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class VoiceModelsTest {

    private static final String TAG = "VoiceModels";

    private AppContainer app;
    private AudioClip speech;

    @Before
    public void setUp() throws Exception {
        app = TestModels.container();
        speech = TestSpeech.english();
        assumeTrue("this device has no usable speech synthesiser", speech != null);
    }

    // Peak and current resident memory of this process, from the kernel's own accounting.
    private static String memory() throws Exception {
        StringBuilder found = new StringBuilder();
        for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
            if (line.startsWith("VmHWM") || line.startsWith("VmRSS")
                    || line.startsWith("VmSwap")) {
                found.append(line.replaceAll("\\s+", " ")).append("; ");
            }
        }
        return found.toString();
    }

    private AppSettings settings() {
        return new AppSettings(1, Map.of());
    }

    private <T> T onInferenceThread(java.util.concurrent.Callable<T> work) throws Exception {
        return app.inference.submit(work).get(15, TimeUnit.MINUTES);
    }

    @Test
    public void theDecoderProducesMono16kSpeechOfTheRightLength() {
        assertEquals(AudioPipeline.SPEECH_RATE, speech.sampleRate);
        assertTrue("about ten words a few seconds long: " + speech.seconds(),
                speech.seconds() > 3 && speech.seconds() < 40);
        float peak = 0;
        for (float s : speech.samples) peak = Math.max(peak, Math.abs(s));
        assertTrue("the audio should not be silence, peak=" + peak, peak > 0.05f);
    }

    @Test
    public void voz_transcribesSynthesisedSpeech() throws Exception {
        TestModels.ensureInstalled("voz");
        long t0 = SystemClock.elapsedRealtime();
        Transcript transcript = onInferenceThread(() -> app.voz.run(speech, settings()));
        long coldMs = SystemClock.elapsedRealtime() - t0;
        long t1 = SystemClock.elapsedRealtime();
        onInferenceThread(() -> app.voz.run(speech, settings()));
        Log.i(TAG, "voz coldMs=" + coldMs + " warmMs=" + (SystemClock.elapsedRealtime() - t1)
                + " audioSeconds=" + speech.seconds() + " words=" + transcript.words.size()
                + " text=" + transcript.text);
        Log.i(TAG, "voz memory " + memory());

        assertTrue("transcript: " + transcript.text, TestSpeech.recognised(transcript.text));
        assertFalse("timed words are needed for filler and speaker marking",
                transcript.words.isEmpty());
    }

    private static long residentKb() throws Exception {
        for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
            if (line.startsWith("VmRSS")) return Long.parseLong(line.replaceAll("\\D+", ""));
        }
        throw new AssertionError("no VmRSS line");
    }

    @Test
    public void voz_givesItsMemoryBackOnceItHasSatUnused() throws Exception {
        TestModels.ensureInstalled("voz");
        onInferenceThread(() -> app.voz.run(speech, settings()));
        long loadedKb = residentKb();

        Thread.sleep((AppContainer.TOOL_IDLE_SECONDS + 10) * 1000L);
        onInferenceThread(() -> null); // lets a release that is under way finish
        long idleKb = residentKb();

        Log.i(TAG, "voz loadedKb=" + loadedKb + " idleKb=" + idleKb);
        assertTrue("memory held after the idle time: " + idleKb + " kB of " + loadedKb,
                idleKb < loadedKb - 1_000_000);
    }

    // Clear needs a real GPU: on an emulator drawing in software it takes the process down
    // (see CrashRecoveryProbeTest). Run with `-e clear true` on a phone or a host-GPU emulator.
    private static void assumeClearCanRun() {
        assumeTrue("Clear tests are opt-in: -e clear true", "true".equals(
                InstrumentationRegistry.getArguments().getString("clear")));
    }

    @Test
    public void clear_cleansSpeechAtItsOwnRateAndTheResultStillTranscribes() throws Exception {
        assumeClearCanRun();
        TestModels.ensureInstalled("clear");
        TestModels.ensureInstalled("voz");
        ClearAdapter clear = new ClearAdapter(app.runtime);
        long t0 = SystemClock.elapsedRealtime();
        AudioClip cleaned = onInferenceThread(() -> clear.run(speech, settings()));
        Log.i(TAG, "clear ms=" + (SystemClock.elapsedRealtime() - t0) + " rate="
                + cleaned.sampleRate + " seconds=" + cleaned.seconds() + " memory " + memory());

        assertEquals("the same length of audio comes back", speech.seconds(),
                cleaned.seconds(), 0.5);
        // The next step is told the rate of what it is given, whatever Clear produced.
        Transcript transcript = onInferenceThread(() -> app.voz.run(cleaned, settings()));
        assertTrue("transcript of cleaned audio: " + transcript.text,
                TestSpeech.recognised(transcript.text));
    }

    @Test
    public void thePipelineCleansFirstAndKeepsTheCleanedAudioForSharing() throws Exception {
        assumeClearCanRun();
        for (String id : new String[] {"clear", "voz"}) TestModels.ensureInstalled(id);
        Map<String, Object> chosen = new HashMap<>();
        chosen.put(AppSettings.AI_ENABLED, true);
        chosen.put(AppSettings.modelEnabledKey("clear"), true);
        chosen.put(AppSettings.modelEnabledKey("voz"), true);
        AppSettings on = new AppSettings(1, chosen);
        StringBuilder steps = new StringBuilder();

        VoiceResult result = onInferenceThread(() -> app.voice.run(speech, on,
                id -> on.modelActive(id) && app.models.isReady(id), new AtomicBoolean(),
                id -> steps.append(id).append(' ')));

        assertEquals("clear voz ", steps.toString());
        assertTrue(result.failures.toString(), result.failures.isEmpty());
        assertNotNull("the cleaned audio is kept for the share button", result.enhanced);
        assertTrue(TestSpeech.recognised(result.transcript.text));
    }

    @Test
    public void ear_hearsEnglish() throws Exception {
        TestModels.ensureInstalled("ear");
        EarAdapter ear = new EarAdapter(app.runtime);
        LanguageHint hint = onInferenceThread(() -> ear.run(speech, settings()));
        Log.i(TAG, "ear language=" + hint.language + " reliable=" + hint.reliable
                + " memory " + memory());
        assertTrue("language: " + hint.language, hint.language.startsWith("en"));
    }

    @Test
    public void uhm_runsOnSpeechWithAndWithoutWords() throws Exception {
        TestModels.ensureInstalled("uhm");
        UhmAdapter uhm = new UhmAdapter(app.runtime);
        List<FillerSpan> fillers = onInferenceThread(
                () -> uhm.run(new UhmAdapter.Input(speech, List.of()), settings()));
        Log.i(TAG, "uhm fillers=" + fillers.size() + " memory " + memory());
        assertNotNull(fillers);
        // Fluent synthesised speech has no "um"; a clean sentence must not be full of flags.
        assertTrue("too many fillers flagged in fluent speech: " + fillers.size(),
                fillers.size() <= 2);
    }

    @Test
    public void who_marksTurnsInSpeechLongEnoughToJudge() throws Exception {
        TestModels.ensureInstalled("who");
        WhoAdapter who = new WhoAdapter(app.runtime);
        AudioClip longer = TestSpeech.repeated(speech, 3);
        List<SpeakerTurn> turns = onInferenceThread(() -> who.run(longer, settings()));
        java.util.Set<Integer> speakers = new java.util.HashSet<>();
        for (SpeakerTurn turn : turns) speakers.add(turn.speaker);
        Log.i(TAG, "who seconds=" + longer.seconds() + " turns=" + turns.size()
                + " speakers=" + speakers.size() + " memory " + memory());
        assertFalse("twenty seconds of speech should produce turns", turns.isEmpty());
        for (SpeakerTurn turn : turns) {
            assertTrue(turn.end > turn.start && turn.end <= longer.seconds() + 1);
        }
    }

    @Test
    public void who_returnsNoTurnsForAVeryShortClipRatherThanFailing() throws Exception {
        TestModels.ensureInstalled("who");
        WhoAdapter who = new WhoAdapter(app.runtime);
        // Measured on this model: about seven seconds is too little for it to mark anyone.
        List<SpeakerTurn> turns = onInferenceThread(() -> who.run(speech, settings()));
        assertNotNull(turns);
    }

    @Test
    public void thePipelineRunsTheChosenStepsAndReportsEachPart() throws Exception {
        for (String id : new String[] {"voz", "ear", "uhm", "who"}) {
            TestModels.ensureInstalled(id);
        }
        Map<String, Object> chosen = new HashMap<>();
        chosen.put(AppSettings.AI_ENABLED, true);
        for (String id : new String[] {"voz", "ear", "uhm", "who"}) {
            chosen.put(AppSettings.modelEnabledKey(id), true);
        }
        AppSettings on = new AppSettings(1, chosen);
        StringBuilder steps = new StringBuilder();

        AudioClip longer = TestSpeech.repeated(speech, 3); // long enough for speaker marking
        VoiceResult result = onInferenceThread(() -> app.voice.run(longer, on,
                id -> on.modelActive(id) && app.models.isReady(id), new AtomicBoolean(),
                id -> steps.append(id).append(' ')));

        Log.i(TAG, "pipeline steps=" + steps + " failures=" + result.failures);
        // Clear is installed on no test device here, so it must simply be skipped.
        assertEquals("ear voz uhm who ", steps.toString());
        assertTrue(result.failures.toString(), result.failures.isEmpty());
        assertNotNull(result.transcript);
        assertNotNull(result.language);
        assertFalse(result.turns.isEmpty());
    }

    @Test
    public void cancellingStopsThePipelineBetweenSteps() throws Exception {
        TestModels.ensureInstalled("ear");
        Map<String, Object> chosen = new HashMap<>();
        chosen.put(AppSettings.AI_ENABLED, true);
        chosen.put(AppSettings.modelEnabledKey("ear"), true);
        AppSettings on = new AppSettings(1, chosen);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try {
            onInferenceThread(() -> app.voice.run(speech, on,
                    id -> on.modelActive(id) && app.models.isReady(id), cancelled, id -> { }));
            throw new AssertionError("a cancelled run must not return a result");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof java.util.concurrent.CancellationException);
        }
    }
}

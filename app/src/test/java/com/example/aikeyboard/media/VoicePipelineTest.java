// The order and the rules of the voice pipeline, with stand-in models: which steps run,
// what each one is given, what happens when one fails, and how cancelling behaves. The real
// models are exercised on a device in VoiceModelsTest.
package com.example.aikeyboard.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.adapters.UhmAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.media.Voice.VoiceResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class VoicePipelineTest {

    private static final AppSettings SETTINGS = new AppSettings(1, Map.of());
    private static final AudioClip RECORDED = new AudioClip(new float[16_000], 16_000);
    private static final AudioClip CLEANED = new AudioClip(new float[48_000], 48_000);
    private static final List<TimedWord> WORDS = List.of(new TimedWord("hello", 0.1, 0.5));

    private final List<String> ran = new ArrayList<>();
    // What each stand-in was handed, by model id.
    private AudioClip earHeard;
    private AudioClip vozHeard;
    private UhmAdapter.Input uhmGot;
    private String failing;

    /** A stand-in model: notes that it ran, fails if told to, otherwise answers. */
    private <I, O> ModelAdapter<I, O> model(String id, Function<I, O> answer) {
        return new ModelAdapter<I, O>() {
            @Override
            public String modelId() {
                return id;
            }

            @Override
            public O run(I input, AppSettings settings) throws Exception {
                ran.add(id);
                if (id.equals(failing)) throw new IllegalStateException("no good");
                return answer.apply(input);
            }

            @Override
            public void release() {
            }
        };
    }

    private AudioPipeline pipeline() {
        return new AudioPipeline(
                this.<AudioClip, AudioClip>model("clear", audio -> CLEANED),
                this.<AudioClip, LanguageHint>model("ear", audio -> {
                    earHeard = audio;
                    return new LanguageHint("en", true);
                }),
                this.<AudioClip, Transcript>model("voz", audio -> {
                    vozHeard = audio;
                    return new Transcript("hello", WORDS);
                }),
                this.<UhmAdapter.Input, List<FillerSpan>>model("uhm", input -> {
                    uhmGot = input;
                    return List.of(new FillerSpan(0, 0.05, "um"));
                }),
                this.<AudioClip, List<SpeakerTurn>>model("who",
                        audio -> List.of(new SpeakerTurn(0, 0, 1))));
    }

    private VoiceResult run(String... usable) {
        Set<String> on = Set.copyOf(Arrays.asList(usable));
        return pipeline().run(RECORDED, SETTINGS, on::contains, new AtomicBoolean(),
                id -> { });
    }

    @Test
    public void everyStepRunsInOrderWhenAllAreUsable() {
        VoiceResult result = run("clear", "ear", "voz", "uhm", "who");

        assertEquals(List.of("clear", "ear", "voz", "uhm", "who"), ran);
        assertTrue(result.failures.isEmpty());
        assertEquals("hello", result.transcript.text);
        assertEquals("en", result.language.language);
        assertEquals(1, result.fillers.size());
        assertEquals(1, result.turns.size());
    }

    @Test
    public void onlyTheUsableStepsRunAndTheRestAreSimplyAbsent() {
        VoiceResult result = run("voz");

        assertEquals(List.of("voz"), ran);
        assertNull(result.language);
        assertNull(result.enhanced);
        assertTrue(result.fillers.isEmpty());
        assertTrue(result.turns.isEmpty());
        assertTrue(result.failures.isEmpty());
    }

    @Test
    public void theLaterStepsHearTheCleanedAudioAndItIsKeptForSharing() {
        VoiceResult result = run("clear", "ear", "voz", "uhm");

        assertSame(CLEANED, earHeard);
        assertSame(CLEANED, vozHeard);
        assertSame(CLEANED, uhmGot.audio);
        assertSame(CLEANED, result.enhanced);
    }

    @Test
    public void withoutCleanupTheStepsHearTheRecordingItself() {
        run("ear", "voz");

        assertSame(RECORDED, earHeard);
        assertSame(RECORDED, vozHeard);
    }

    @Test
    public void theFillerStepIsGivenTheWordsTheTranscriptFound() {
        run("voz", "uhm");
        assertEquals(WORDS, uhmGot.words);
    }

    @Test
    public void theFillerStepRunsOnAudioAloneWhenThereIsNoTranscript() {
        run("uhm");
        assertTrue(uhmGot.words.isEmpty());
    }

    @Test
    public void aStepThatFailsIsNamedAndTheOthersStillRun() {
        failing = "ear";

        VoiceResult result = run("ear", "voz", "who");

        assertEquals(List.of("ear", "voz", "who"), ran);
        assertEquals(List.of("ear: no good"), result.failures);
        assertNull(result.language);
        assertEquals("hello", result.transcript.text);
    }

    @Test
    public void whenCleanupFailsTheOtherStepsUseTheRecording() {
        failing = "clear";

        VoiceResult result = run("clear", "voz");

        assertSame(RECORDED, vozHeard);
        assertNull(result.enhanced);
        assertEquals(List.of("clear: no good"), result.failures);
    }

    @Test
    public void progressNamesEachStepAsItStarts() {
        List<String> announced = new ArrayList<>();
        pipeline().run(RECORDED, SETTINGS, id -> !id.equals("clear"), new AtomicBoolean(),
                announced::add);
        assertEquals(List.of("ear", "voz", "uhm", "who"), announced);
    }

    @Test
    public void cancellingStopsBeforeTheNextStepAndReturnsNothing() {
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            // Cancelled while the transcript step is announced: nothing after ear may run.
            pipeline().run(RECORDED, SETTINGS, id -> true, cancelled, id -> {
                if (id.equals("ear")) cancelled.set(true);
            });
            fail("a cancelled run must not return a result");
        } catch (CancellationException expected) {
            assertEquals("the step already started finishes; no later one starts",
                    List.of("clear", "ear"), ran);
        }
    }
}

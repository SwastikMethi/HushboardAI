// The audio arithmetic the voice tools rely on: channel mix-down, sample-rate conversion,
// WAV encoding, and how a transcript is worded with fillers and speakers marked.
package com.example.aikeyboard.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.TimedWord;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class AudioPipelineTest {

    @Test
    public void stereoIsMixedDownByAveragingTheChannels() {
        short[] interleaved = {1000, 3000, -2000, -4000, 32767, 32767};
        float[] mono = AudioPipeline.toMono(interleaved, 2);
        assertEquals(3, mono.length);
        assertEquals(2000 / 32768f, mono[0], 1e-6);
        assertEquals(-3000 / 32768f, mono[1], 1e-6);
        assertEquals(32767 / 32768f, mono[2], 1e-6);
    }

    @Test
    public void monoIsOnlyScaled() {
        float[] mono = AudioPipeline.toMono(new short[] {16384, -16384}, 1);
        assertArrayEquals(new float[] {0.5f, -0.5f}, mono, 1e-6f);
    }

    @Test
    public void resamplingKeepsTheDurationAndTheShapeOfTheSound() {
        // One second of a 100 Hz tone at 48 kHz, brought down to 16 kHz.
        float[] tone = new float[48_000];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) Math.sin(2 * Math.PI * 100 * i / 48_000.0);
        }
        AudioClip out = AudioPipeline.resample(new AudioClip(tone, 48_000), 16_000);

        assertEquals(16_000, out.sampleRate);
        assertEquals(16_000, out.samples.length);
        assertEquals(1.0, out.seconds(), 1e-9);
        // A quarter of the way through one cycle (2.5 ms = sample 40) the tone peaks.
        assertEquals(1.0f, out.samples[40], 0.01f);
        assertEquals(0.0f, out.samples[80], 0.01f);
    }

    @Test
    public void resamplingToTheSameRateReturnsTheSameAudio() {
        AudioClip clip = new AudioClip(new float[] {0.1f, 0.2f}, 16_000);
        assertTrue(AudioPipeline.resample(clip, 16_000) == clip);
    }

    @Test
    public void wavFilesCarryACorrectHeaderAnd16BitSamples() {
        AudioClip clip = new AudioClip(new float[] {0f, 0.5f, -1f, 2f}, 16_000);
        byte[] wav = AudioPipeline.toWav(clip);

        assertEquals(44 + 8, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(wav.length - 8, header.getInt(4));
        assertEquals(1, header.getShort(20));        // PCM
        assertEquals(1, header.getShort(22));        // mono
        assertEquals(16_000, header.getInt(24));
        assertEquals(16, header.getShort(34));       // bits per sample
        assertEquals(8, header.getInt(40));          // data bytes
        assertEquals(0, header.getShort(44));
        assertEquals(16384, header.getShort(46));
        assertEquals(-32768, header.getShort(48));
        assertEquals("out-of-range samples are clipped, not wrapped", 32767, header.getShort(50));
    }

    @Test
    public void aTranscriptCanMarkFillersWithoutRemovingThem() {
        List<TimedWord> words = Arrays.asList(
                new TimedWord("so", 0.0, 0.2), new TimedWord("um", 0.3, 0.6),
                new TimedWord("hello", 0.7, 1.1));
        List<FillerSpan> fillers = List.of(new FillerSpan(0.28, 0.62, "um"));

        assertEquals("so [um] hello", VoiceText.withFillersMarked(words, fillers));
    }

    @Test
    public void aTranscriptCanBeSplitIntoSpeakerTurns() {
        List<TimedWord> words = Arrays.asList(
                new TimedWord("hi", 0.0, 0.3), new TimedWord("there", 0.4, 0.8),
                new TimedWord("hello", 2.0, 2.5));
        List<SpeakerTurn> turns = Arrays.asList(
                new SpeakerTurn(0, 0.0, 1.0), new SpeakerTurn(1, 1.8, 3.0));

        assertEquals("Speaker 1: hi there\nSpeaker 2: hello",
                VoiceText.bySpeaker(words, turns, List.of()));
    }

    @Test
    public void wordsOutsideEveryTurnStayWithTheNearestEarlierSpeaker() {
        List<TimedWord> words = Arrays.asList(
                new TimedWord("hi", 0.0, 0.3), new TimedWord("okay", 1.2, 1.5));
        List<SpeakerTurn> turns = List.of(new SpeakerTurn(0, 0.0, 1.0));

        assertEquals("Speaker 1: hi okay", VoiceText.bySpeaker(words, turns, List.of()));
    }

    // ---- What the preview shows for a finished run ----

    private static final List<TimedWord> WORDS = Arrays.asList(
            new TimedWord("so", 0.0, 0.2), new TimedWord("um", 0.3, 0.6),
            new TimedWord("hello", 0.7, 1.1));
    private static final Voice.Transcript TRANSCRIPT = new Voice.Transcript("so um hello", WORDS);
    private static final List<FillerSpan> FILLERS = List.of(new FillerSpan(0.28, 0.62, "um"));

    private static Voice.VoiceResult result(Voice.Transcript transcript,
            List<FillerSpan> fillers, List<SpeakerTurn> turns) {
        return new Voice.VoiceResult(transcript, null, fillers, turns, null, List.of());
    }

    @Test
    public void thePreviewIsThePlainTranscriptWhenNothingWasMarked() {
        assertEquals("so um hello", VoiceText.preview(result(TRANSCRIPT, List.of(), List.of())));
    }

    @Test
    public void thePreviewMarksFillersWhenTheyWereFound() {
        assertEquals("so [um] hello", VoiceText.preview(result(TRANSCRIPT, FILLERS, List.of())));
    }

    @Test
    public void thePreviewIsLaidOutBySpeakerWhenTurnsWereFound() {
        List<SpeakerTurn> turns = List.of(new SpeakerTurn(0, 0.0, 2.0));
        assertEquals("Speaker 1: so [um] hello",
                VoiceText.preview(result(TRANSCRIPT, FILLERS, turns)));
    }

    @Test
    public void thePreviewIsEmptyWhenThereIsNoTranscriptToShow() {
        assertEquals("", VoiceText.preview(result(null, FILLERS, List.of())));
        // A transcript without word timings cannot be marked, so it is shown as it came.
        Voice.Transcript untimed = new Voice.Transcript("so um hello", List.of());
        assertEquals("so um hello", VoiceText.preview(result(untimed, FILLERS, List.of())));
    }
}

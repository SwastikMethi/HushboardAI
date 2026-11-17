// Runs the voice models over one piece of audio the user recorded or imported, in a fixed
// order, using only the models that are switched on and installed. Also holds the plain
// audio arithmetic (mix-down, resampling, WAV encoding) the voice tools need.
package com.example.aikeyboard.media;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.adapters.UhmAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.media.Voice.VoiceResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class AudioPipeline {

    /** The rate audio is captured at and converted to: the usual rate for speech models. */
    public static final int SPEECH_RATE = 16_000;

    public interface Progress {
        /** Called as each step starts, with the model's id. */
        void onStep(String modelId);
    }

    private final ModelAdapter<AudioClip, AudioClip> clear;
    private final ModelAdapter<AudioClip, LanguageHint> ear;
    private final ModelAdapter<AudioClip, Transcript> voz;
    private final ModelAdapter<UhmAdapter.Input, List<FillerSpan>> uhm;
    private final ModelAdapter<AudioClip, List<SpeakerTurn>> who;

    public AudioPipeline(ModelAdapter<AudioClip, AudioClip> clear,
            ModelAdapter<AudioClip, LanguageHint> ear,
            ModelAdapter<AudioClip, Transcript> voz,
            ModelAdapter<UhmAdapter.Input, List<FillerSpan>> uhm,
            ModelAdapter<AudioClip, List<SpeakerTurn>> who) {
        this.clear = clear;
        this.ear = ear;
        this.voz = voz;
        this.uhm = uhm;
        this.who = who;
    }

    /**
     * Cleanup first (so later steps hear the cleaner audio), then language, transcript,
     * fillers and speakers. Blocking: call on the inference thread. A step that fails is
     * reported and the rest still run. Cancelling takes effect between steps, because a model
     * call that has started cannot be interrupted; it throws CancellationException.
     */
    public VoiceResult run(AudioClip recorded, AppSettings settings, Predicate<String> usable,
            AtomicBoolean cancelled, Progress progress) {
        List<String> failures = new ArrayList<>();
        AudioClip audio = recorded;
        AudioClip enhanced = null;
        LanguageHint language = null;
        Transcript transcript = null;
        List<FillerSpan> fillers = List.of();
        List<SpeakerTurn> turns = List.of();

        if (begin("clear", usable, cancelled, progress)) {
            try {
                enhanced = clear.run(audio, settings);
                // Each model is told the rate of what it is given, so the cleaned audio can
                // be passed on at whatever rate the cleanup step produced.
                audio = enhanced;
            } catch (Exception e) {
                failures.add("clear: " + reason(e));
            }
        }
        if (begin("ear", usable, cancelled, progress)) {
            try {
                language = ear.run(audio, settings);
            } catch (Exception e) {
                failures.add("ear: " + reason(e));
            }
        }
        if (begin("voz", usable, cancelled, progress)) {
            try {
                transcript = voz.run(audio, settings);
            } catch (Exception e) {
                failures.add("voz: " + reason(e));
            }
        }
        if (begin("uhm", usable, cancelled, progress)) {
            try {
                fillers = uhm.run(new UhmAdapter.Input(audio,
                        transcript == null ? List.of() : transcript.words), settings);
            } catch (Exception e) {
                failures.add("uhm: " + reason(e));
            }
        }
        if (begin("who", usable, cancelled, progress)) {
            try {
                turns = who.run(audio, settings);
            } catch (Exception e) {
                failures.add("who: " + reason(e));
            }
        }
        if (cancelled.get()) throw new CancellationException();
        return new VoiceResult(transcript, language, fillers, turns, enhanced, failures);
    }

    private static boolean begin(String modelId, Predicate<String> usable,
            AtomicBoolean cancelled, Progress progress) {
        if (cancelled.get()) throw new CancellationException();
        if (!usable.test(modelId)) return false;
        progress.onStep(modelId);
        return true;
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---- Audio arithmetic ----

    /** Interleaved 16-bit samples to mono floats between -1 and 1, averaging the channels. */
    public static float[] toMono(short[] interleaved, int channels) {
        float[] mono = new float[interleaved.length / channels];
        for (int frame = 0; frame < mono.length; frame++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[frame * channels + c];
            mono[frame] = sum / (32768f * channels);
        }
        return mono;
    }

    // ponytail: linear interpolation with no low-pass filter, so content above half the
    // target rate aliases. Fine for speech going to 16 kHz; use a filtered resampler if
    // music or export quality ever matters.
    public static AudioClip resample(AudioClip clip, int targetRate) {
        if (clip.sampleRate == targetRate) return clip;
        int length = (int) Math.round(clip.samples.length * (double) targetRate / clip.sampleRate);
        float[] out = new float[length];
        double step = clip.sampleRate / (double) targetRate;
        for (int i = 0; i < length; i++) {
            double position = i * step;
            int index = (int) position;
            float a = clip.samples[Math.min(index, clip.samples.length - 1)];
            float b = clip.samples[Math.min(index + 1, clip.samples.length - 1)];
            out[i] = (float) (a + (b - a) * (position - index));
        }
        return new AudioClip(out, targetRate);
    }

    /** A complete 16-bit mono WAV file, for export. */
    public static byte[] toWav(AudioClip clip) {
        int dataBytes = clip.samples.length * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1);          // PCM, one channel
        wav.putInt(clip.sampleRate).putInt(clip.sampleRate * 2); // byte rate
        wav.putShort((short) 2).putShort((short) 16);         // block align, bits per sample
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (float sample : clip.samples) {
            int value = Math.round(sample * 32768f);
            wav.putShort((short) Math.max(-32768, Math.min(32767, value)));
        }
        return wav.array();
    }
}

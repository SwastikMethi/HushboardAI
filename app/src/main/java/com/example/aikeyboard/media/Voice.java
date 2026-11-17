// Value types for the voice tools. Audio and transcripts live in memory for the current
// session only; nothing here is written to disk unless the user exports it.
package com.example.aikeyboard.media;

import com.example.aikeyboard.ai.Suggestion.LanguageHint;

import java.util.Collections;
import java.util.List;

public final class Voice {

    private Voice() {}

    /** Mono audio as samples between -1 and 1. */
    public static final class AudioClip {
        public final float[] samples;
        public final int sampleRate;

        public AudioClip(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public double seconds() {
            return samples.length / (double) sampleRate;
        }
    }

    /** One recognised word with its place in the audio, in seconds. */
    public static final class TimedWord {
        public final String text;
        public final double start;
        public final double end;

        public TimedWord(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Transcript {
        public final String text;
        public final List<TimedWord> words;

        public Transcript(String text, List<TimedWord> words) {
            this.text = text;
            this.words = Collections.unmodifiableList(words);
        }
    }

    /** A stretch of audio flagged as a filler ("um", "uh"). Flagged only: nothing is cut. */
    public static final class FillerSpan {
        public final double start;
        public final double end;
        public final String type;

        public FillerSpan(double start, double end, String type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    /** A stretch of audio attributed to one speaker, numbered from 0. */
    public static final class SpeakerTurn {
        public final int speaker;
        public final double start;
        public final double end;

        public SpeakerTurn(int speaker, double start, double end) {
            this.speaker = speaker;
            this.start = start;
            this.end = end;
        }
    }

    /** Everything one run of the voice pipeline produced. Any part may be absent. */
    public static final class VoiceResult {
        public final Transcript transcript;
        public final LanguageHint language;
        public final List<FillerSpan> fillers;
        public final List<SpeakerTurn> turns;
        /** The cleaned-up audio, when the cleanup step ran. */
        public final AudioClip enhanced;
        /** Steps that were chosen but failed, by model name, with the reason. */
        public final List<String> failures;

        public VoiceResult(Transcript transcript, LanguageHint language,
                List<FillerSpan> fillers, List<SpeakerTurn> turns, AudioClip enhanced,
                List<String> failures) {
            this.transcript = transcript;
            this.language = language;
            this.fillers = Collections.unmodifiableList(fillers);
            this.turns = Collections.unmodifiableList(turns);
            this.enhanced = enhanced;
            this.failures = Collections.unmodifiableList(failures);
        }
    }
}

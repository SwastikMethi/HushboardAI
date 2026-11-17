// Words a transcript for preview: fillers marked in brackets, speakers on their own lines.
// Marking only: the audio and the words themselves are never cut. Pure logic.
package com.example.aikeyboard.media;

import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.media.Voice.VoiceResult;

import java.util.List;

public final class VoiceText {

    private VoiceText() {}

    /**
     * What the preview shows for a finished run: laid out by speaker if turns were found,
     * fillers bracketed if any were, otherwise the transcript as it came. Empty when there is
     * no transcript.
     */
    public static String preview(VoiceResult result) {
        if (result.transcript == null) return "";
        List<TimedWord> words = result.transcript.words;
        // Marking needs each word's place in the audio.
        if (words.isEmpty()) return result.transcript.text;
        if (!result.turns.isEmpty()) return bySpeaker(words, result.turns, result.fillers);
        if (!result.fillers.isEmpty()) return withFillersMarked(words, result.fillers);
        return result.transcript.text;
    }

    /** The transcript with each filler word shown as "[um]". */
    public static String withFillersMarked(List<TimedWord> words, List<FillerSpan> fillers) {
        StringBuilder text = new StringBuilder();
        for (TimedWord word : words) {
            if (text.length() > 0) text.append(' ');
            text.append(mark(word, fillers));
        }
        return text.toString();
    }

    /**
     * One line per run of words by the same speaker: "Speaker 1: …". A word belongs to the
     * turn its middle falls in; a word between turns stays with the speaker before it.
     */
    public static String bySpeaker(List<TimedWord> words, List<SpeakerTurn> turns,
            List<FillerSpan> fillers) {
        StringBuilder text = new StringBuilder();
        int current = -1;
        for (TimedWord word : words) {
            int speaker = speakerAt(middle(word), turns, current);
            if (speaker != current) {
                if (text.length() > 0) text.append('\n');
                text.append("Speaker ").append(speaker + 1).append(':');
                current = speaker;
            }
            text.append(' ').append(mark(word, fillers));
        }
        return text.toString();
    }

    private static String mark(TimedWord word, List<FillerSpan> fillers) {
        double middle = middle(word);
        for (FillerSpan filler : fillers) {
            if (middle >= filler.start && middle <= filler.end) return "[" + word.text + "]";
        }
        return word.text;
    }

    private static int speakerAt(double time, List<SpeakerTurn> turns, int previous) {
        for (SpeakerTurn turn : turns) {
            if (time >= turn.start && time <= turn.end) return turn.speaker;
        }
        if (previous >= 0) return previous;
        return turns.isEmpty() ? 0 : turns.get(0).speaker;
    }

    private static double middle(TimedWord word) {
        return (word.start + word.end) / 2;
    }
}

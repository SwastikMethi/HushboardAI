// What a child's voice recording comes to in Kids Chat, worked out from what the voice models
// report: the words to show and send, with "um"s and other people's voices left out. The
// words then go through the same checks as a typed message. Pure logic.
package com.example.aikeyboard.ui.chat;

import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.media.Voice.VoiceResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChatVoice {

    public enum Kind {
        /** Something was said and written down. */
        WORDS,
        /** Nothing that could be written down. */
        NOTHING,
        /** Speech that is surely not English, which the checks cannot read. */
        OTHER_LANGUAGE
    }

    public static final class Heard {
        public final Kind kind;
        /** For {@link Kind#WORDS}: what the child said. */
        public final String words;
        /** True when a second voice was heard and its words were left out. */
        public final boolean otherVoice;

        Heard(Kind kind, String words, boolean otherVoice) {
            this.kind = kind;
            this.words = words;
            this.otherVoice = otherVoice;
        }
    }

    private ChatVoice() {}

    public static Heard heard(VoiceResult result) {
        // The spoken-language model's word comes first: a recording it is sure is not
        // English is not written down at all. A guess changes nothing.
        if (result.language != null && result.language.reliable
                && !ChatChecks.ENGLISH.equals(result.language.language)) {
            return new Heard(Kind.OTHER_LANGUAGE, null, false);
        }
        Transcript transcript = result.transcript;
        if (transcript == null || transcript.text.trim().isEmpty()) {
            return new Heard(Kind.NOTHING, null, false);
        }

        int child = longestSpeaker(result.turns);
        StringBuilder kept = new StringBuilder();
        boolean leftOut = false;
        boolean otherVoice = false;
        for (TimedWord word : transcript.words) {
            double middle = (word.start + word.end) / 2;
            if (isFiller(result.fillers, middle)) {
                leftOut = true;
                continue;
            }
            // Only when two voices were told apart, and this word is plainly the other's.
            int speaker = child < 0 ? -1 : speakerAt(result.turns, middle);
            if (speaker >= 0 && speaker != child) {
                leftOut = true;
                otherVoice = true;
                continue;
            }
            if (kept.length() > 0) kept.append(' ');
            kept.append(word.text.trim());
        }
        // Nothing left out: the words as the speech model wrote them, punctuation and all.
        if (!leftOut) return new Heard(Kind.WORDS, transcript.text.trim(), false);
        if (kept.length() == 0) return new Heard(Kind.NOTHING, null, otherVoice);
        return new Heard(Kind.WORDS, kept.toString(), otherVoice);
    }

    private static boolean isFiller(List<FillerSpan> fillers, double at) {
        for (FillerSpan filler : fillers) {
            if (at >= filler.start && at < filler.end) return true;
        }
        return false;
    }

    // The voice that speaks for longest is taken for the child's. -1 when fewer than two
    // voices were told apart, and then nothing is left out on that account.
    private static int longestSpeaker(List<SpeakerTurn> turns) {
        Map<Integer, Double> spoke = new HashMap<>();
        for (SpeakerTurn turn : turns) {
            spoke.merge(turn.speaker, turn.end - turn.start, Double::sum);
        }
        if (spoke.size() < 2) return -1;
        int longest = -1;
        for (Map.Entry<Integer, Double> voice : spoke.entrySet()) {
            if (longest < 0 || voice.getValue() > spoke.get(longest)) longest = voice.getKey();
        }
        return longest;
    }

    // Whose turn a moment falls in, or -1 for a moment between turns.
    private static int speakerAt(List<SpeakerTurn> turns, double at) {
        for (SpeakerTurn turn : turns) {
            if (at >= turn.start && at < turn.end) return turn.speaker;
        }
        return -1;
    }
}

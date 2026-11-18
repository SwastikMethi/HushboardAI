// What Kids Chat makes of a voice recording: the words as written down, with "um"s and other
// people's voices left out, or nothing, or speech it cannot understand.
package com.example.aikeyboard.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.media.Voice.VoiceResult;
import com.example.aikeyboard.ui.chat.ChatVoice.Heard;
import com.example.aikeyboard.ui.chat.ChatVoice.Kind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ChatVoiceTest {

    @Test
    public void theWordsComeBackAsTheSpeechModelWroteThem() {
        Heard heard = ChatVoice.heard(result(said("I am going to the park."), null,
                List.of(), List.of()));

        assertEquals(Kind.WORDS, heard.kind);
        assertEquals("I am going to the park.", heard.words);
        assertFalse(heard.otherVoice);
    }

    @Test
    public void umsAreLeftOut() {
        // Each word takes half a second, so "um" runs from 0.5 to 1.0.
        Heard heard = ChatVoice.heard(result(said("I um am happy"), null,
                List.of(new FillerSpan(0.45, 1.05, "um")), List.of()));

        assertEquals("I am happy", heard.words);
        assertFalse(heard.otherVoice);
    }

    @Test
    public void aSecondVoiceIsLeftOutAndTheChildIsTold() {
        // "hello there friend" in the first 1.5 seconds, "dinner time" after it.
        Heard heard = ChatVoice.heard(result(said("hello there friend dinner time"), null,
                List.of(), List.of(new SpeakerTurn(0, 0, 1.5), new SpeakerTurn(1, 1.5, 2.5))));

        assertEquals("hello there friend", heard.words);
        assertTrue(heard.otherVoice);
    }

    @Test
    public void theVoiceThatSpeaksLongestIsTakenForTheChilds() {
        Heard heard = ChatVoice.heard(result(said("hush now can we go to the park"), null,
                List.of(), List.of(new SpeakerTurn(0, 0, 1.0), new SpeakerTurn(1, 1.0, 4.0))));

        assertEquals("can we go to the park", heard.words);
    }

    @Test
    public void withOneVoiceNothingIsLeftOut() {
        Heard heard = ChatVoice.heard(result(said("Can we go to the park?"), null,
                List.of(), List.of(new SpeakerTurn(0, 0, 1.2), new SpeakerTurn(0, 1.4, 3.0))));

        assertEquals("Can we go to the park?", heard.words);
        assertFalse(heard.otherVoice);
    }

    @Test
    public void aWordThatFallsBetweenTurnsIsKept() {
        // "and" sits in the gap from 1.0 to 1.5 that belongs to nobody.
        Heard heard = ChatVoice.heard(result(said("cats dogs and birds bye"), null,
                List.of(), List.of(new SpeakerTurn(0, 0, 1.0), new SpeakerTurn(0, 1.5, 2.0),
                        new SpeakerTurn(1, 2.0, 2.5))));

        assertEquals("cats dogs and birds", heard.words);
    }

    @Test
    public void silenceIsNothing() {
        assertEquals(Kind.NOTHING,
                ChatVoice.heard(result(null, null, List.of(), List.of())).kind);
        assertEquals(Kind.NOTHING,
                ChatVoice.heard(result(said("  "), null, List.of(), List.of())).kind);
    }

    @Test
    public void nothingButUmsIsNothing() {
        Heard heard = ChatVoice.heard(result(said("um uh"), null,
                List.of(new FillerSpan(0, 1.0, "um")), List.of()));

        assertEquals(Kind.NOTHING, heard.kind);
    }

    @Test
    public void speechThatIsSurelyNotEnglishIsNotWrittenDown() {
        Heard heard = ChatVoice.heard(result(said("bahut sundar ghar hai"),
                new LanguageHint("hi", true), List.of(), List.of()));

        assertEquals(Kind.OTHER_LANGUAGE, heard.kind);
    }

    @Test
    public void aGuessAtAnotherSpokenLanguageChangesNothing() {
        Heard heard = ChatVoice.heard(result(said("see you soon"),
                new LanguageHint("nl", false), List.of(), List.of()));

        assertEquals(Kind.WORDS, heard.kind);
        assertEquals("see you soon", heard.words);
    }

    /** A transcript whose words each take half a second, one after the other. */
    private static Transcript said(String text) {
        List<TimedWord> words = new ArrayList<>();
        if (!text.trim().isEmpty()) {
            String[] spoken = text.trim().split(" ");
            for (int i = 0; i < spoken.length; i++) {
                words.add(new TimedWord(spoken[i], i * 0.5, i * 0.5 + 0.5));
            }
        }
        return new Transcript(text, words);
    }

    private static VoiceResult result(Transcript transcript, LanguageHint language,
            List<FillerSpan> fillers, List<SpeakerTurn> turns) {
        return new VoiceResult(transcript, language, fillers, turns, null, List.of());
    }
}

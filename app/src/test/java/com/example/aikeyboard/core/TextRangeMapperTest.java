// Model spans are UTF-16 offsets that can land inside a character as the user sees it. These
// tests pin down how a span is brought to whole characters and mapped into the editor.
package com.example.aikeyboard.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TextRangeMapperTest {

    // Man + joiner + woman + joiner + girl: 8 UTF-16 units, one character on screen.
    private static final String FAMILY =
            "👨‍👩‍👧";
    private static final String COMBINING_DIAERESIS = "̈";

    private static String cut(String text, int[] range) {
        return text.substring(range[0], range[1]);
    }

    @Test
    public void aSpanAlreadyOnCharacterBoundariesIsLeftAlone() {
        String text = "mail anna@example.hu now";
        assertArrayEquals(new int[] {5, 20}, TextRangeMapper.wholeCharacters(text, 5, 20));
    }

    @Test
    public void aSpanThatStartsInsideAnEmojiFamilyStartsAfterIt() {
        // Seen with the real model: a name span that began after the family's second joiner.
        String text = FAMILY + " Zoe, hi";
        int[] range = TextRangeMapper.wholeCharacters(text, 5, 12);
        assertEquals("Zoe", cut(text, range));
    }

    @Test
    public void aSpanThatStartsInsideASurrogatePairStartsAfterIt() {
        String text = "😀Anna";
        int[] range = TextRangeMapper.wholeCharacters(text, 1, 6);
        assertEquals("Anna", cut(text, range));
    }

    @Test
    public void aSpanThatEndsBeforeACombiningMarkTakesTheMarkWithItsLetter() {
        String text = "Zoë is here"; // e + combining diaeresis
        int[] range = TextRangeMapper.wholeCharacters(text, 0, 3);
        assertEquals("Zoë", cut(text, range));
    }

    @Test
    public void aSpanThatEndsInsideASurrogatePairTakesTheWholeEmoji() {
        String text = "call 555😀";
        int[] range = TextRangeMapper.wholeCharacters(text, 5, 9);
        assertEquals("555😀", cut(text, range));
    }

    @Test
    public void aSpanThatHoldsNothingButACutCharacterIsDropped() {
        String text = FAMILY + " hi";
        assertNull(TextRangeMapper.wholeCharacters(text, 3, 5));
    }

    @Test
    public void spansOutsideTheTextAreRejected() {
        assertNull(TextRangeMapper.wholeCharacters("abc", 2, 9));
        assertNull(TextRangeMapper.wholeCharacters("abc", -1, 2));
        assertNull(TextRangeMapper.wholeCharacters("abc", 2, 2));
    }

    @Test
    public void aSpanInAPartialWindowMapsToItsPlaceInTheEditor() {
        // The editor shared text starting at offset 120 of the field.
        assertArrayEquals(new int[] {125, 140}, TextRangeMapper.editorRange(120, 5, 20));
    }

    @Test
    public void withoutAKnownWindowOffsetThereIsNoEditorRange() {
        assertNull(TextRangeMapper.editorRange(-1, 5, 20));
    }
}

// Brings model spans to whole characters and maps them into the editor. Model offsets are
// UTF-16 units and can land inside what the user sees as one character (an emoji family, a
// letter with a combining accent). Pure logic.
package com.example.aikeyboard.core;

import java.text.BreakIterator;

public final class TextRangeMapper {

    private TextRangeMapper() {}

    /**
     * Adjusts [start, end) so it never cuts a character in two. A span that starts inside a
     * character begins after it (and after the space that follows): the model swallowed a
     * neighbour, typically an emoji, that is not part of the detected item. A span that ends
     * inside a character takes the rest of it, so an accent stays with its letter.
     * Returns {start, end}, or null if nothing is left or the span is out of bounds.
     */
    public static int[] wholeCharacters(String text, int start, int end) {
        if (start < 0 || end > text.length() || start >= end) return null;
        BreakIterator characters = BreakIterator.getCharacterInstance();
        characters.setText(text);
        if (!characters.isBoundary(start)) {
            start = characters.following(start);
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        }
        if (end < text.length() && !characters.isBoundary(end)) {
            end = characters.following(end);
        }
        return start < end ? new int[] {start, end} : null;
    }

    /**
     * Where a span inside the text window sits in the editor's own text. Null when the editor
     * did not say where the window starts: then no edit may be attempted, only a preview.
     */
    public static int[] editorRange(int windowOffset, int start, int end) {
        return windowOffset < 0 ? null : new int[] {windowOffset + start, windowOffset + end};
    }
}

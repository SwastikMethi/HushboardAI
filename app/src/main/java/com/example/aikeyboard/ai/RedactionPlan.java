// Turns detected spans into guarded edits, and into the edits that undo them. Pure logic;
// the editor bridge applies an edit only if the text it expects is still there.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.core.TextEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RedactionPlan {

    private RedactionPlan() {}

    /** Edits that replace each span with its placeholder, in document order. */
    public static List<TextEdit> mask(List<SensitiveSpan> spans, int windowOffset) {
        List<TextEdit> edits = new ArrayList<>();
        for (SensitiveSpan span : spans) {
            edits.add(new TextEdit(windowOffset + span.start, windowOffset + span.end,
                    span.original, span.placeholder));
        }
        edits.sort(Comparator.comparingInt((TextEdit e) -> e.start));
        return edits;
    }

    /**
     * Edits that put the originals back after {@code applied} was applied. Each placeholder
     * has moved by the length change of every edit before it.
     */
    public static List<TextEdit> undo(List<TextEdit> applied) {
        List<TextEdit> undo = new ArrayList<>();
        int shift = 0;
        for (TextEdit edit : applied) {
            int start = edit.start + shift;
            undo.add(new TextEdit(start, start + edit.replacement.length(), edit.replacement,
                    edit.expected));
            shift += edit.replacement.length() - (edit.end - edit.start);
        }
        return undo;
    }

    /** The analysed text with every span masked, for a preview the user can copy. */
    public static String maskedText(String text, List<SensitiveSpan> spans) {
        StringBuilder masked = new StringBuilder(text);
        List<TextEdit> edits = mask(spans, 0);
        for (int i = edits.size() - 1; i >= 0; i--) {
            masked.replace(edits.get(i).start, edits.get(i).end, edits.get(i).replacement);
        }
        return masked.toString();
    }
}

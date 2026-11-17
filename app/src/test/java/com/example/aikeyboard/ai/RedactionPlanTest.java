// Turning detected spans into guarded edits, and into the edits that put them back.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;

import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.core.TextEdit;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RedactionPlanTest {

    private static final String TEXT = "mail anna@example.hu or call 555 0100 now";
    private final List<SensitiveSpan> spans = Arrays.asList(
            new SensitiveSpan("phone", 29, 37, "555 0100", "[PHONE_1]"),
            new SensitiveSpan("email", 5, 20, "anna@example.hu", "[EMAIL_1]"));

    private static String apply(String text, List<TextEdit> edits) {
        StringBuilder out = new StringBuilder(text);
        // Apply from the end so earlier offsets stay valid, as the editor bridge does.
        for (int i = edits.size() - 1; i >= 0; i--) {
            TextEdit edit = edits.get(i);
            assertEquals(edit.expected, out.substring(edit.start, edit.end));
            out.replace(edit.start, edit.end, edit.replacement);
        }
        return out.toString();
    }

    @Test
    public void maskEditsAreInDocumentOrderAndCarryTheTextTheyExpect() {
        List<TextEdit> edits = RedactionPlan.mask(spans, 0);
        assertEquals(2, edits.size());
        assertEquals(5, edits.get(0).start);
        assertEquals("anna@example.hu", edits.get(0).expected);
        assertEquals("[EMAIL_1]", edits.get(0).replacement);
        assertEquals(29, edits.get(1).start);
    }

    @Test
    public void maskEditsAreShiftedByTheWindowOffset() {
        List<TextEdit> edits = RedactionPlan.mask(spans, 100);
        assertEquals(105, edits.get(0).start);
        assertEquals(120, edits.get(0).end);
    }

    @Test
    public void applyingTheMaskGivesTheMaskedText() {
        String masked = apply(TEXT, RedactionPlan.mask(spans, 0));
        assertEquals("mail [EMAIL_1] or call [PHONE_1] now", masked);
        assertEquals(masked, RedactionPlan.maskedText(TEXT, spans));
    }

    @Test
    public void undoEditsFindThePlaceholdersWhereTheyNowAreAndRestoreTheOriginals() {
        List<TextEdit> mask = RedactionPlan.mask(spans, 0);
        String masked = apply(TEXT, mask);

        List<TextEdit> undo = RedactionPlan.undo(mask);

        assertEquals("[EMAIL_1]", undo.get(0).expected);
        assertEquals("anna@example.hu", undo.get(0).replacement);
        assertEquals(TEXT, apply(masked, undo));
    }
}

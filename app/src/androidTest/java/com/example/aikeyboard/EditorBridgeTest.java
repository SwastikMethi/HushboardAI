// Exercises EditorBridge against a real EditText's own InputConnection, in-process, so the
// framework's actual editing behaviour (composing region, batch edits, offsets) is under test.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.ai.RedactionPlan;
import com.example.aikeyboard.core.TextEdit;
import com.example.aikeyboard.ime.EditorBridge;

import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EditorBridgeTest {

    private static final String FAMILY = "👨‍👩‍👧"; // 8 UTF-16 units

    private EditText field;
    private EditorBridge bridge;

    @Before
    public void setUp() {
        getInstrumentation().runOnMainSync(() -> {
            field = new EditText(ApplicationProvider.getApplicationContext());
            InputConnection connection = field.onCreateInputConnection(new EditorInfo());
            bridge = new EditorBridge(() -> connection);
        });
    }

    private void given(String text, int selStart, int selEnd) {
        getInstrumentation().runOnMainSync(() -> {
            field.setText(text);
            field.setSelection(selStart, selEnd);
        });
    }

    private boolean replace(int start, int end, String expected, String replacement) {
        boolean[] result = new boolean[1];
        getInstrumentation().runOnMainSync(
                () -> result[0] = bridge.replaceRange(start, end, expected, replacement));
        return result[0];
    }

    private String text() {
        String[] result = new String[1];
        getInstrumentation().runOnMainSync(() -> result[0] = field.getText().toString());
        return result[0];
    }

    private int cursor() {
        int[] result = new int[1];
        getInstrumentation().runOnMainSync(() -> result[0] = field.getSelectionStart());
        return result[0];
    }

    @Test
    public void commitText_replacesTheSelection() {
        given("abcd", 1, 3);
        getInstrumentation().runOnMainSync(() -> bridge.commitText("X"));
        assertEquals("aXd", text());
        assertEquals(2, cursor());
    }

    @Test
    public void textBeforeCursor_isBoundedAndStopsAtTheCursor() {
        given("hello world", 5, 5);
        String[] result = new String[2];
        getInstrumentation().runOnMainSync(() -> {
            result[0] = bridge.textBeforeCursor(100);
            result[1] = bridge.textBeforeCursor(3);
        });
        assertEquals("hello", result[0]);
        assertEquals("llo", result[1]);
    }

    @Test
    public void cursorPosition_isTheAbsoluteOffset() {
        given(FAMILY + " ok", FAMILY.length() + 1, FAMILY.length() + 1);
        int[] result = new int[1];
        getInstrumentation().runOnMainSync(() -> result[0] = bridge.cursorPosition());
        assertEquals(9, result[0]);
    }

    private EditorBridge.Draft draft(int before, int after) {
        EditorBridge.Draft[] result = new EditorBridge.Draft[1];
        getInstrumentation().runOnMainSync(() -> result[0] = bridge.readDraft(before, after));
        return result[0];
    }

    @Test
    public void readDraft_returnsOnlyABoundedWindowAndSaysItIsPartial() {
        given("0123456789abcdefghij", 12, 12);
        EditorBridge.Draft draft = draft(5, 3);
        assertEquals("789abcde", draft.text);
        assertEquals(5, draft.selectionStart);
        assertEquals(5, draft.selectionEnd);
        assertEquals(7, draft.offset);
        assertTrue(draft.partial);
    }

    @Test
    public void readDraft_knowsWhenItHasTheWholeDraft() {
        given("hi", 2, 2);
        EditorBridge.Draft draft = draft(100, 100);
        assertEquals("hi", draft.text);
        assertEquals(0, draft.offset);
        assertFalse(draft.partial);
    }

    @Test
    public void readDraft_includesTheSelection() {
        given("hello world", 6, 11);
        EditorBridge.Draft draft = draft(100, 100);
        assertEquals("hello world", draft.text);
        assertEquals(6, draft.selectionStart);
        assertEquals(11, draft.selectionEnd);
    }

    private static final String TWO_ITEMS = "mail anna@example.hu or call 555 0100 now";
    private static final List<TextEdit> MASK = Arrays.asList(
            new TextEdit(5, 20, "anna@example.hu", "[EMAIL_1]"),
            new TextEdit(29, 37, "555 0100", "[PHONE_1]"));

    private boolean replaceAll(List<TextEdit> edits) {
        boolean[] result = new boolean[1];
        getInstrumentation().runOnMainSync(() -> result[0] = bridge.replaceRanges(edits));
        return result[0];
    }

    @Test
    public void replaceRanges_appliesEveryEditAsOne() {
        given(TWO_ITEMS, TWO_ITEMS.length(), TWO_ITEMS.length());
        assertTrue(replaceAll(MASK));
        assertEquals("mail [EMAIL_1] or call [PHONE_1] now", text());
        assertEquals("the cursor stays at the end", text().length(), cursor());
    }

    @Test
    public void replaceRanges_changesNothingIfAnyRangeNoLongerMatches() {
        String edited = "mail anna@example.hu or call 555 9999 now"; // the number was retyped
        given(edited, edited.length(), edited.length());
        assertFalse(replaceAll(MASK));
        assertEquals("not even the email may be replaced", edited, text());
    }

    @Test
    public void replaceRanges_refusesEditsThatOverlapAndChangesNothing() {
        given("call anna smith now", 19, 19);
        // Two findings that share "anna": each matches the text, together they would mangle it.
        List<TextEdit> overlapping = Arrays.asList(
                new TextEdit(5, 15, "anna smith", "[NAME_1]"),
                new TextEdit(5, 9, "anna", "[GIVEN_1]"));
        boolean[] applied = new boolean[1];
        getInstrumentation().runOnMainSync(
                () -> applied[0] = bridge.replaceRanges(overlapping));

        assertFalse("overlapping edits must be refused as a whole", applied[0]);
        assertEquals("call anna smith now", text());
    }

    @Test
    public void replaceRanges_aMaskCanBeUndoneAndTheUndoIsGuardedToo() {
        given(TWO_ITEMS, TWO_ITEMS.length(), TWO_ITEMS.length());
        assertTrue(replaceAll(MASK));
        List<TextEdit> undo = RedactionPlan.undo(MASK);

        assertTrue(replaceAll(undo));
        assertEquals(TWO_ITEMS, text());
        // A second undo finds no placeholders and must refuse.
        assertFalse(replaceAll(undo));
        assertEquals(TWO_ITEMS, text());
    }

    @Test
    public void replaceRange_swapsTheExpectedTextAndKeepsTheCursorAfterIt() {
        given("helo world", 10, 10);
        assertTrue(replace(0, 4, "helo", "hello"));
        assertEquals("hello world", text());
        assertEquals(11, cursor());
    }

    @Test
    public void replaceRange_putsTheCursorAfterAWordItWasTouching() {
        given("see you tomorow", 15, 15);
        assertTrue(replace(8, 15, "tomorow", "tomorrow"));
        assertEquals("see you tomorrow", text());
        assertEquals(16, cursor());
    }

    @Test
    public void replaceRange_leavesACursorBeforeTheRangeWhereItWas() {
        given("hi anna@example.hu", 2, 2);
        assertTrue(replace(3, 18, "anna@example.hu", "[EMAIL_1]"));
        assertEquals("hi [EMAIL_1]", text());
        assertEquals(2, cursor());
    }

    @Test
    public void replaceRange_refusesWhenTheTextChangedUnderneath() {
        given("hell world", 10, 10);
        assertFalse(replace(0, 4, "helo", "hello"));
        assertEquals("hell world", text());
        assertEquals(10, cursor());
    }

    @Test
    public void replaceRange_refusesARangeOutsideTheText() {
        given("abc", 3, 3);
        assertFalse(replace(2, 9, "c", "x"));
        assertEquals("abc", text());
    }

    @Test
    public void replaceRange_usesUtf16OffsetsAcrossEmojiSequences() {
        String original = FAMILY + " anna@example.hu ok";
        given(original, original.length(), original.length());
        int start = FAMILY.length() + 1;
        int end = start + "anna@example.hu".length();
        assertTrue(replace(start, end, "anna@example.hu", "[EMAIL_1]"));
        assertEquals(FAMILY + " [EMAIL_1] ok", text());
    }
}

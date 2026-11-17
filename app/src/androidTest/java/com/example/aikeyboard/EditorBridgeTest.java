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

import com.example.aikeyboard.ime.EditorBridge;

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

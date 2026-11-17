// End-to-end keyboard behaviour: real touches on the real IME window, typing into the debug
// editor host. Keys are found by the accessibility labels TalkBack users hear.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyboardImeTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        device = UiDevice.getInstance(getInstrumentation());
        // A test run may start on a fresh install, so the test selects the keyboard itself.
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        // Emulators report a hardware keyboard, which otherwise hides the soft keyboard.
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() throws Exception {
        device.setOrientationNatural();
        scenario.close();
    }

    private void focus(String fieldId) {
        UiObject2 field = device.wait(Until.findObject(By.res(PKG, fieldId)), TIMEOUT_MS);
        assertNotNull("field not found: " + fieldId, field);
        field.click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    private UiObject2 key(String label) {
        UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
        assertNotNull("key not found: " + label, key);
        return key;
    }

    private void tap(String... labels) {
        for (String label : labels) key(label).click();
        device.waitForIdle();
    }

    private String text(int fieldId) {
        String[] result = new String[1];
        scenario.onActivity(a ->
                result[0] = ((EditText) a.findViewById(fieldId)).getText().toString());
        return result[0];
    }

    private void given(int fieldId, String text) {
        scenario.onActivity(a -> {
            EditText field = a.findViewById(fieldId);
            field.setText(text);
            field.setSelection(text.length());
        });
        device.waitForIdle();
    }

    @Test
    public void typesLettersSpaceAndPunctuation() {
        focus("field_plain");
        tap("h", "i", "Space", "y", "o", "u", ".");
        assertEquals("hi you.", text(R.id.field_plain));
    }

    @Test
    public void shiftCapitalisesOnlyTheNextLetter() {
        focus("field_plain");
        tap("Shift", "H", "i");
        assertEquals("Hi", text(R.id.field_plain));
    }

    @Test
    public void sentenceStartsAreCapitalisedWhereTheFieldAsksForIt() {
        focus("field_multiline");
        tap("H", "i", ".", "Space", "O", "k");
        assertEquals("Hi. Ok", text(R.id.field_multiline));
    }

    @Test
    public void symbolLayersTypeDigitsAndReturnToLetters() {
        focus("field_plain");
        tap("Symbols", "1", "2", "More symbols", "<", "Letters", "a");
        assertEquals("12<a", text(R.id.field_plain));
    }

    @Test
    public void backspaceDeletesOneCharacterPerTap() {
        focus("field_plain");
        given(R.id.field_plain, "abc");
        tap("Backspace");
        assertEquals("ab", text(R.id.field_plain));
    }

    @Test
    public void backspaceRemovesAWholeEmojiFamilyAtOnce() {
        focus("field_plain");
        given(R.id.field_plain, "a👨‍👩‍👧");
        tap("Backspace");
        assertEquals("a", text(R.id.field_plain));
    }

    @Test
    public void holdingBackspaceKeepsDeleting() {
        focus("field_plain");
        given(R.id.field_plain, "abcdefghijklmnopqrstuvwxyz");
        key("Backspace").click(1500);
        device.waitForIdle();
        assertTrue("held backspace should delete many characters, left: "
                + text(R.id.field_plain), text(R.id.field_plain).length() < 20);
    }

    @Test
    public void enterPerformsTheFieldsOwnAction() {
        EditorTestActivity.lastEditorAction = -1;
        focus("field_send");
        tap("a", "Send");
        assertEquals(EditorInfo.IME_ACTION_SEND, EditorTestActivity.lastEditorAction);
        assertEquals("the keyboard never edits text for an action", "a", text(R.id.field_send));
    }

    @Test
    public void enterInsertsANewLineInAMultiLineField() {
        focus("field_multiline");
        tap("A", "New line", "B");
        assertEquals("A\nB", text(R.id.field_multiline));
    }

    @Test
    public void backspaceAndEnterLeaveTheAppInTouchMode() {
        focus("field_multiline");
        tap("A", "New line", "B", "Backspace");
        assertEquals("A\n", text(R.id.field_multiline));

        boolean[] touchMode = new boolean[1];
        scenario.onActivity(a -> touchMode[0] = a.getWindow().getDecorView().isInTouchMode());

        // A key event that looks like a hardware key takes the app out of touch mode, and
        // focus highlights appear on whatever was last touched.
        assertTrue("Enter and Backspace must be sent as on-screen keyboard keys",
                touchMode[0]);
    }

    @Test
    public void emojiPanelInsertsAnEmojiAndReturnsToLetters() {
        focus("field_plain");
        tap("Emoji");
        UiObject2 emoji = device.wait(Until.findObject(By.descStartsWith("😀")), TIMEOUT_MS);
        assertNotNull("no emoji in the panel", emoji);
        emoji.click();
        tap("Letters", "a");
        assertEquals("😀a", text(R.id.field_plain));
    }

    @Test
    public void aWordSuggestionReplacesTheWordBeingTyped() {
        focus("field_plain");
        tap("t", "o", "m", "o");
        UiObject2 suggestion = device.wait(
                Until.findObject(By.res(PKG, "suggestion_1").text("tomorrow")), TIMEOUT_MS);
        assertNotNull("expected 'tomorrow' as the first suggestion", suggestion);
        suggestion.click();
        device.waitForIdle();
        assertEquals("tomorrow ", text(R.id.field_plain));
    }

    @Test
    public void passwordFieldsGetNoWordSuggestions() {
        focus("field_password");
        tap("t", "o", "m", "o");
        assertEquals("tomo", text(R.id.field_password));
        assertNull("a password must never be completed",
                device.findObject(By.res(PKG, "suggestion_1").text("tomorrow")));
    }

    @Test
    public void keyboardKeepsWorkingAfterRotation() throws Exception {
        focus("field_plain");
        tap("a");
        device.setOrientationLandscape();
        focus("field_plain");
        tap("b");
        device.setOrientationNatural();
        focus("field_plain");
        tap("c");
        assertEquals("abc", text(R.id.field_plain));
        assertFalse(text(R.id.field_plain).isEmpty());
    }

    @Test
    public void keysSitAboveTheNavigationArea() {
        focus("field_plain");
        assertTrue(key("Space").getVisibleBounds().bottom < device.getDisplayHeight());
    }
}

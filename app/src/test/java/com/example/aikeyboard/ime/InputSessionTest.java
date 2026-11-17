// How a field's declared type decides what the keyboard may do in it.
package com.example.aikeyboard.ime;

import static android.text.InputType.TYPE_CLASS_NUMBER;
import static android.text.InputType.TYPE_CLASS_PHONE;
import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
import static android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
import static android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
import static android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
import static android.text.InputType.TYPE_TEXT_VARIATION_URI;
import static android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
import static android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
import static android.view.inputmethod.EditorInfo.IME_ACTION_SEND;
import static android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InputSessionTest {

    @Test
    public void everyPasswordVariationIsAPassword() {
        assertTrue(InputSession.isPassword(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD));
        assertTrue(InputSession.isPassword(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        assertTrue(InputSession.isPassword(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD));
        assertTrue(InputSession.isPassword(TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD));
    }

    @Test
    public void ordinaryFieldsAreNotPasswords() {
        assertFalse(InputSession.isPassword(TYPE_CLASS_TEXT));
        assertFalse(InputSession.isPassword(TYPE_CLASS_NUMBER));
        assertFalse(InputSession.isPassword(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
    }

    @Test
    public void wordSuggestionsAreOnlyForOrdinaryText() {
        assertTrue(InputSession.allowsWordSuggestions(TYPE_CLASS_TEXT));
        assertFalse(InputSession.allowsWordSuggestions(
                TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD));
        assertFalse(InputSession.allowsWordSuggestions(
                TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        assertFalse(InputSession.allowsWordSuggestions(
                TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI));
        assertFalse(InputSession.allowsWordSuggestions(
                TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        assertFalse(InputSession.allowsWordSuggestions(TYPE_CLASS_NUMBER));
        assertFalse(InputSession.allowsWordSuggestions(TYPE_CLASS_PHONE));
    }

    @Test
    public void theNoLearningFlagIsRead() {
        assertTrue(InputSession.isNoLearning(IME_ACTION_SEND | IME_FLAG_NO_PERSONALIZED_LEARNING));
        assertFalse(InputSession.isNoLearning(IME_ACTION_SEND));
    }
}

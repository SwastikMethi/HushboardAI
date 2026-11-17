// One connection to one input field. The id says "which field", the generation says "which
// version of its text"; together they let late results be recognised and dropped. A session
// identifies a field, never a person or a conversation.
package com.example.aikeyboard.ime;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

public final class InputSession {

    private static int nextId = 1;

    public final int id;
    public final String editorPackage;
    public final int inputType;
    public final int imeOptions;
    public final boolean password;
    public final boolean noLearning;
    public final boolean wordSuggestions;

    private int generation;

    public InputSession(EditorInfo info) {
        id = nextId++;
        editorPackage = info.packageName;
        inputType = info.inputType;
        imeOptions = info.imeOptions;
        password = isPassword(inputType);
        noLearning = isNoLearning(imeOptions);
        wordSuggestions = allowsWordSuggestions(inputType);
    }

    public int generation() {
        return generation;
    }

    /** Call on every text or cursor change, whoever caused it. */
    public void bumpGeneration() {
        generation++;
    }

    public static boolean isPassword(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT:
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            default:
                return false;
        }
    }

    public static boolean isNoLearning(int imeOptions) {
        return (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
    }

    /** Word completions only make sense in ordinary prose fields. */
    public static boolean allowsWordSuggestions(int inputType) {
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        if (isPassword(inputType)) return false;
        if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_URI
                && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }
}

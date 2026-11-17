// The keys of each keyboard layer. Pure data with no Android types, so it is tested on the JVM.
package com.example.aikeyboard.ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class KeyboardLayout {

    public enum Layer { LETTERS, SYMBOLS, MORE_SYMBOLS }

    // A character key commits its label. Function keys carry one of the negative codes.
    public static final int CODE_TEXT = 0;
    public static final int CODE_SHIFT = -1;
    public static final int CODE_BACKSPACE = -2;
    public static final int CODE_SYMBOLS = -3;
    public static final int CODE_LETTERS = -4;
    public static final int CODE_MORE_SYMBOLS = -5;
    public static final int CODE_ENTER = -6;
    public static final int CODE_EMOJI = -7;
    public static final int CODE_SPACE = -8;

    // A smiling face followed by U+FE0E, which asks for the outline glyph, not the colour emoji.
    private static final String EMOJI_GLYPH = "☺︎";

    public static final class Key {
        public final int code;
        /** For a character key, the lower-case text it commits; for a function key, its glyph. */
        public final String label;
        /** Width in key units; a full row is ten units wide. */
        public final float weight;

        Key(int code, String label, float weight) {
            this.code = code;
            this.label = label;
            this.weight = weight;
        }

        public boolean isText() {
            return code == CODE_TEXT;
        }
    }

    private static final List<List<Key>> LETTERS = Arrays.asList(
            chars("qwertyuiop"),
            chars("asdfghjkl"),
            row(fn(CODE_SHIFT, "⇧", 1.5f), chars("zxcvbnm"), fn(CODE_BACKSPACE, "⌫", 1.5f)),
            row(fn(CODE_SYMBOLS, "?123", 1.5f), chars(","), fn(CODE_EMOJI, EMOJI_GLYPH, 1f),
                    fn(CODE_SPACE, " ", 4f), chars("."), fn(CODE_ENTER, "⏎", 1.5f)));

    private static final List<List<Key>> SYMBOLS = Arrays.asList(
            chars("1234567890"),
            chars("@#$_&-+()/"),
            row(fn(CODE_MORE_SYMBOLS, "=\\<", 1.5f), chars("*\"':;!?"),
                    fn(CODE_BACKSPACE, "⌫", 1.5f)),
            row(fn(CODE_LETTERS, "ABC", 1.5f), chars(","), fn(CODE_EMOJI, EMOJI_GLYPH, 1f),
                    fn(CODE_SPACE, " ", 4f), chars("."), fn(CODE_ENTER, "⏎", 1.5f)));

    private static final List<List<Key>> MORE_SYMBOLS = Arrays.asList(
            chars("~`|•√π÷×¶∆"),
            chars("£¢€¥^°={}\\"),
            row(fn(CODE_SYMBOLS, "?123", 1.5f), chars("%©®™✓[]"),
                    fn(CODE_BACKSPACE, "⌫", 1.5f)),
            row(fn(CODE_LETTERS, "ABC", 1.5f), chars("<"), fn(CODE_SPACE, " ", 5f), chars(">"),
                    fn(CODE_ENTER, "⏎", 1.5f)));

    private KeyboardLayout() {}

    public static List<List<Key>> rows(Layer layer) {
        switch (layer) {
            case SYMBOLS:
                return SYMBOLS;
            case MORE_SYMBOLS:
                return MORE_SYMBOLS;
            default:
                return LETTERS;
        }
    }

    private static Key fn(int code, String label, float weight) {
        return new Key(code, label, weight);
    }

    // One unit-wide character key per code point of the string.
    private static List<Key> chars(String characters) {
        List<Key> keys = new ArrayList<>();
        characters.codePoints().forEach(cp ->
                keys.add(new Key(CODE_TEXT, new String(Character.toChars(cp)), 1f)));
        return keys;
    }

    // Flattens a mix of single keys and key lists into one unmodifiable row.
    @SuppressWarnings("unchecked")
    private static List<Key> row(Object... parts) {
        List<Key> keys = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Key) keys.add((Key) part);
            else keys.addAll((List<Key>) part);
        }
        return Collections.unmodifiableList(keys);
    }
}

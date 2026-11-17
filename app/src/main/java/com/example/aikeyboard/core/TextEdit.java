// One guarded replacement in the editor: put `replacement` where `expected` is, and only if
// `expected` is really still there.
package com.example.aikeyboard.core;

public final class TextEdit {

    /** Absolute UTF-16 range in the editor's text, end exclusive. */
    public final int start;
    public final int end;
    public final String expected;
    public final String replacement;

    public TextEdit(int start, int end, String expected, String replacement) {
        this.start = start;
        this.end = end;
        this.expected = expected;
        this.replacement = replacement;
    }
}

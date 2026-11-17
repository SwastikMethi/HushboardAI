// The text the editor exposed at one moment, with enough identity to tell later whether a
// result still belongs to it. Held in memory only; never logged or stored.
package com.example.aikeyboard.ai;

public final class DraftSnapshot {

    /** Which input connection this came from. Identifies a field, not a person. */
    public final int sessionId;
    /** Increases with every text or cursor change in that field. */
    public final int generation;
    public final String editorPackage;
    /** The bounded text the editor returned around the cursor. May be only part of the draft. */
    public final String text;
    /** Selection within {@link #text}, in UTF-16 units. */
    public final int selectionStart;
    public final int selectionEnd;
    /** Absolute UTF-16 offset of {@link #text} in the editor, or -1 if the editor hid it. */
    public final int offset;
    /** True when the editor may hold more text than it returned. */
    public final boolean partial;

    public DraftSnapshot(int sessionId, int generation, String editorPackage, String text,
            int selectionStart, int selectionEnd, int offset, boolean partial) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.editorPackage = editorPackage;
        this.text = text;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.offset = offset;
        this.partial = partial;
    }

    public String textBeforeCursor() {
        return text.substring(0, Math.min(selectionStart, text.length()));
    }

    /** Whether the other snapshot is the very same moment of the same field. */
    public boolean sameMoment(DraftSnapshot other) {
        return other != null && sessionId == other.sessionId && generation == other.generation;
    }
}

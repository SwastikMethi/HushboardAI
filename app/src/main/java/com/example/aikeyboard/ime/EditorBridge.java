// The only class allowed to read or change the host app's text. Everything else in the
// keyboard goes through here, so every edit is validated in one place.
package com.example.aikeyboard.ime;

import android.content.ClipDescription;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import com.example.aikeyboard.core.TextEdit;

import java.util.List;
import java.util.function.Supplier;

public final class EditorBridge {

    // The connection changes with every focused field, so it is fetched fresh for each call.
    private final Supplier<InputConnection> connection;

    public EditorBridge(Supplier<InputConnection> connection) {
        this.connection = connection;
    }

    /** Inserts text at the cursor, replacing any selection. */
    public void commitText(CharSequence text) {
        InputConnection ic = connection.get();
        if (ic != null) ic.commitText(text, 1);
    }

    /** Whether the focused field has said it takes content of this type (a picture, say). */
    public static boolean accepts(EditorInfo info, String mimeType) {
        if (info == null) return false;
        for (String accepted : EditorInfoCompat.getContentMimeTypes(info)) {
            if (ClipDescription.compareMimeTypes(mimeType, accepted)) return true;
        }
        return false;
    }

    /**
     * Hands the field a picture, with permission to read it. Only where the field has said
     * it takes this type. Returns false if it does not, or if the host app turns it down,
     * so the caller can offer another way.
     */
    public boolean commitContent(EditorInfo info, Uri uri, String mimeType, String label) {
        InputConnection ic = connection.get();
        if (ic == null || !accepts(info, mimeType)) return false;
        InputContentInfoCompat content = new InputContentInfoCompat(uri,
                new ClipDescription(label, new String[] {mimeType}), null);
        return InputConnectionCompat.commitContent(ic, info, content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
    }

    /**
     * Deletes backwards as a real key press, so the host editor applies its own rules for
     * selections and multi-code-point characters such as emoji families.
     */
    public void backspace() {
        InputConnection ic = connection.get();
        if (ic == null) return;
        sendKey(ic, KeyEvent.KEYCODE_DEL);
    }

    // A key press marked as coming from an on-screen keyboard, with real times, the way
    // InputMethodService.sendDownUpKeyEvents sends one. Unmarked, some Android versions
    // treat it as a hardware key and take the app out of touch mode.
    private static void sendKey(InputConnection ic, int keyCode) {
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        ic.sendKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
    }

    /**
     * Presses Enter the way the field asked for: its own action (Send, Search, Next…) when it
     * declared one, otherwise a plain Enter key so the host decides between newline and submit.
     * The keyboard never performs an action the user did not press.
     */
    public void enter(int imeOptions) {
        InputConnection ic = connection.get();
        if (ic == null) return;
        int action = imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean plainEnter = (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                || action == EditorInfo.IME_ACTION_NONE
                || action == EditorInfo.IME_ACTION_UNSPECIFIED;
        if (plainEnter) {
            sendKey(ic, KeyEvent.KEYCODE_ENTER);
        } else {
            ic.performEditorAction(action);
        }
    }

    /** A bounded piece of the field's text around the cursor, as the editor chose to expose it. */
    public static final class Draft {
        public final String text;
        /** Selection within {@link #text}. */
        public final int selectionStart;
        public final int selectionEnd;
        /** Absolute offset of {@link #text} in the field, or -1 if the editor did not say. */
        public final int offset;
        /** True when the field may hold more text than was returned. */
        public final boolean partial;

        Draft(String text, int selectionStart, int selectionEnd, int offset, boolean partial) {
            this.text = text;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
            this.offset = offset;
            this.partial = partial;
        }
    }

    /**
     * Reads at most {@code before} characters before the cursor and {@code after} after it.
     * This is all the keyboard ever sees of a draft: never a whole conversation. Returns
     * null when the editor exposes no text; typing still works without it.
     */
    public Draft readDraft(int before, int after) {
        InputConnection ic = connection.get();
        if (ic == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SurroundingText around = ic.getSurroundingText(before, after, 0);
            if (around != null) {
                String text = around.getText().toString();
                boolean partial = around.getOffset() > 0
                        || text.length() - around.getSelectionEnd() >= after;
                return new Draft(text, around.getSelectionStart(), around.getSelectionEnd(),
                        around.getOffset(), partial);
            }
        }
        CharSequence head = ic.getTextBeforeCursor(before, 0);
        CharSequence tail = ic.getTextAfterCursor(after, 0);
        if (head == null || tail == null) return null;
        CharSequence selected = ic.getSelectedText(0);
        String selection = selected == null ? "" : selected.toString();
        int cursorEnd = cursorPosition();
        int offset = cursorEnd < 0 ? -1 : cursorEnd - selection.length() - head.length();
        boolean partial = head.length() >= before || tail.length() >= after;
        return new Draft(head + selection + tail, head.length(),
                head.length() + selection.length(), offset, partial);
    }

    /** Up to maxChars of text before the cursor; empty when the editor exposes none. */
    public String textBeforeCursor(int maxChars) {
        InputConnection ic = connection.get();
        CharSequence text = ic == null ? null : ic.getTextBeforeCursor(maxChars, 0);
        return text == null ? "" : text.toString();
    }

    /** The cursor's absolute UTF-16 offset, or -1 when the editor does not report it. */
    public int cursorPosition() {
        InputConnection ic = connection.get();
        ExtractedText extracted =
                ic == null ? null : ic.getExtractedText(new ExtractedTextRequest(), 0);
        return extracted == null ? -1 : extracted.startOffset + extracted.selectionEnd;
    }

    /** Whether the field wants the next character capitalised (e.g. a new sentence). */
    public boolean wantsCapital(int inputType) {
        InputConnection ic = connection.get();
        return ic != null && ic.getCursorCapsMode(inputType) != 0;
    }

    /**
     * Replaces the UTF-16 range [start, end) with newText, but only if that range still holds
     * expectedText. Returns false and changes nothing when the editor cannot show the text or
     * the text has moved on; callers must then discard their suggestion rather than guess.
     * The cursor keeps its place relative to the surrounding text.
     */
    public boolean replaceRange(int start, int end, String expectedText, String newText) {
        return replaceRanges(List.of(new TextEdit(start, end, expectedText, newText)));
    }

    /**
     * Applies several guarded replacements as one edit, or none of them. Every range is
     * checked against the editor's current text first; if any has changed, nothing is
     * touched. Edits must be in document order and must not overlap.
     */
    public boolean replaceRanges(List<TextEdit> edits) {
        InputConnection ic = connection.get();
        if (ic == null || edits.isEmpty()) return false;
        ExtractedText extracted = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.text == null) return false;

        int previousEnd = Integer.MIN_VALUE;
        for (TextEdit edit : edits) {
            // Out of order or overlapping: each would pass its own check and together they
            // would mangle the text. Refused as a whole.
            if (edit.start < previousEnd) return false;
            previousEnd = edit.end;
            // An editor may expose only a window of its text; offsets inside it are relative.
            int from = edit.start - extracted.startOffset;
            int to = edit.end - extracted.startOffset;
            if (from < 0 || to > extracted.text.length() || from > to) return false;
            if (!extracted.text.subSequence(from, to).toString().equals(edit.expected)) {
                return false;
            }
        }
        int selStart = extracted.startOffset + extracted.selectionStart;
        int selEnd = extracted.startOffset + extracted.selectionEnd;

        ic.beginBatchEdit();
        // Last edit first, so the offsets of the earlier ones are still right.
        for (int i = edits.size() - 1; i >= 0; i--) {
            TextEdit edit = edits.get(i);
            ic.setComposingRegion(edit.start, edit.end);
            ic.commitText(edit.replacement, 1);
        }
        ic.setSelection(shift(selStart, edits), shift(selEnd, edits));
        ic.endBatchEdit();
        return true;
    }

    // Where a cursor position lands once every edit has been applied.
    private static int shift(int position, List<TextEdit> edits) {
        int moved = 0;
        for (TextEdit edit : edits) {
            if (position < edit.start) break;
            if (position < edit.end) return edit.start + moved + edit.replacement.length();
            moved += edit.replacement.length() - (edit.end - edit.start);
        }
        return position + moved;
    }
}

// Debug-only text field that counts how often a keyboard asks to read its text, so a test
// can prove the keyboard did not look at a field it has no business reading.
package com.example.aikeyboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.SurroundingText;

import androidx.appcompat.widget.AppCompatEditText;

import java.util.concurrent.atomic.AtomicInteger;

public final class CountingEditText extends AppCompatEditText {

    /** Every request for any of the field's text, by any route. */
    public static final AtomicInteger textReads = new AtomicInteger();
    /** Requests for the window of text around the cursor: what a draft is read with. */
    public static final AtomicInteger draftReads = new AtomicInteger();

    public CountingEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static void resetCounts() {
        textReads.set(0);
        draftReads.set(0);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection real = super.onCreateInputConnection(info);
        if (real == null) return null;
        return new InputConnectionWrapper(real, true) {
            @Override
            public CharSequence getTextBeforeCursor(int n, int flags) {
                textReads.incrementAndGet();
                return super.getTextBeforeCursor(n, flags);
            }

            @Override
            public CharSequence getTextAfterCursor(int n, int flags) {
                textReads.incrementAndGet();
                return super.getTextAfterCursor(n, flags);
            }

            @Override
            public CharSequence getSelectedText(int flags) {
                textReads.incrementAndGet();
                return super.getSelectedText(flags);
            }

            @Override
            public SurroundingText getSurroundingText(int before, int after, int flags) {
                textReads.incrementAndGet();
                draftReads.incrementAndGet();
                return super.getSurroundingText(before, after, flags);
            }

            @Override
            public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
                textReads.incrementAndGet();
                return super.getExtractedText(request, flags);
            }
        };
    }
}

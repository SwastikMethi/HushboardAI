// The keyboard's root: the typing area (tool row, strip, keys) sets the height, and every
// panel laid over it is given exactly that height. Without this a panel with a lot in it
// would make the whole keyboard window taller and push the app being typed into up the
// screen; with it, a full panel scrolls inside the keyboard's own footprint.
package com.example.aikeyboard.ime.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

public final class KeyboardFrame extends FrameLayout {

    public KeyboardFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // The first child is the typing area; it is measured even while a panel hides it.
        View typingArea = getChildAt(0);
        measureChildWithMargins(typingArea, widthSpec, 0, heightSpec, 0);
        int height = typingArea.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}

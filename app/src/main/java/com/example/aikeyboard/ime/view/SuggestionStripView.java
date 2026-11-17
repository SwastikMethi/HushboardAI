// The row above the keys. It offers up to three words, or emoji, or one notice with its two
// actions, or one setup hint. Always the same height, so the keyboard never jumps when its
// content comes and goes.
package com.example.aikeyboard.ime.view;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.aikeyboard.R;

import java.util.List;

public final class SuggestionStripView extends LinearLayout {

    /** What the strip is currently offering; decides what a tap means. */
    public enum Kind {
        WORDS,
        EMOJI,
        /** One message followed by two actions; the message gets the most room. */
        NOTICE,
        SETUP
    }

    public interface Listener {
        /** @param index which slot was tapped, from the left, starting at 0 */
        void onStripItemPicked(Kind kind, int index, String text);
    }

    private static final int[] SLOT_IDS = {R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3};

    private final TextView[] slots = new TextView[SLOT_IDS.length];
    private final float wordSize;
    private final float emojiSize;
    private final int plainColor;
    private final int actionColor;
    private Listener listener;
    private Kind kind = Kind.WORDS;

    public SuggestionStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wordSize = getResources().getDimension(R.dimen.suggestion_text_size);
        emojiSize = getResources().getDimension(R.dimen.key_label_size);
        plainColor = context.getColor(R.color.key_label_secondary);
        actionColor = context.getColor(R.color.accent_blue);
        setOrientation(HORIZONTAL);
        setShowDividers(SHOW_DIVIDER_MIDDLE);
        setDividerDrawable(context.getDrawable(R.drawable.strip_divider));
        setDividerPadding((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
                getResources().getDisplayMetrics()));

        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = 0; i < slots.length; i++) {
            int index = i;
            TextView slot = new TextView(context);
            slot.setId(SLOT_IDS[i]);
            slot.setGravity(Gravity.CENTER);
            slot.setSingleLine(true);
            slot.setEllipsize(TextUtils.TruncateAt.END);
            slot.setBackgroundResource(ripple.resourceId);
            slot.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStripItemPicked(kind, index, slot.getText().toString());
                }
            });
            slots[i] = slot;
            addView(slot, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        setItems(Kind.WORDS, List.of());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(Kind newKind, List<String> items) {
        kind = newKind;
        for (int i = 0; i < slots.length; i++) {
            boolean filled = i < items.size();
            boolean noticeText = newKind == Kind.NOTICE && i == 0;
            slots[i].setText(filled ? items.get(i) : "");
            slots[i].setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    newKind == Kind.EMOJI ? emojiSize : noticeText ? wordSize * 0.9f : wordSize);
            // In a notice, the two actions are tinted so they read as buttons.
            slots[i].setTextColor(newKind == Kind.NOTICE && i > 0 ? actionColor : plainColor);
            ((LayoutParams) slots[i].getLayoutParams()).weight = noticeText ? 2.4f : 1f;
            // Empty slots stay in the layout but are neither tappable nor announced.
            slots[i].setVisibility(filled ? VISIBLE : INVISIBLE);
        }
        requestLayout();
    }
}

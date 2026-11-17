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
        /** Emoji from the emoji model, each on its own chip, after the model's name. */
        EMOJI,
        /** One message followed by two actions; the message gets the most room. */
        NOTICE,
        /** Like a notice, but the message is only a label: tapping it does nothing. */
        STATUS,
        SETUP
    }

    public interface Listener {
        /** @param index which slot was tapped, from the left, starting at 0 */
        void onStripItemPicked(Kind kind, int index, String text);
    }

    private static final int[] SLOT_IDS = {R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3};

    private final TextView source;
    private final TextView[] slots = new TextView[SLOT_IDS.length];
    private final float wordSize;
    private final float emojiSize;
    private final int plainColor;
    private final int actionColor;
    private final int plainBackground;
    private final int chipMargin;
    private Listener listener;
    private Kind kind = Kind.WORDS;

    public SuggestionStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wordSize = getResources().getDimension(R.dimen.suggestion_text_size);
        emojiSize = getResources().getDimension(R.dimen.key_label_size);
        plainColor = context.getColor(R.color.key_label_secondary);
        actionColor = context.getColor(R.color.accent_blue);
        chipMargin = dp(4);
        setOrientation(HORIZONTAL);
        setDividerDrawable(context.getDrawable(R.drawable.strip_divider));
        setDividerPadding(dp(10));

        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        plainBackground = ripple.resourceId;

        // Says which model the emoji come from. Not a control: it is read, not tapped.
        source = new TextView(context);
        source.setText(R.string.strip_emoji_source);
        source.setGravity(Gravity.CENTER);
        source.setTextColor(plainColor);
        source.setTextSize(TypedValue.COMPLEX_UNIT_PX, wordSize * 0.9f);
        source.setPadding(dp(14), 0, dp(10), 0);
        addView(source, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        for (int i = 0; i < slots.length; i++) {
            int index = i;
            TextView slot = new TextView(context);
            slot.setId(SLOT_IDS[i]);
            slot.setGravity(Gravity.CENTER);
            slot.setSingleLine(true);
            slot.setEllipsize(TextUtils.TruncateAt.END);
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

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(Kind newKind, List<String> items) {
        kind = newKind;
        boolean chips = newKind == Kind.EMOJI;
        // Words and notices are separated by thin lines; emoji sit on chips instead. With
        // fewer than two things to separate there are no lines at all.
        setShowDividers(chips || items.size() < 2 ? SHOW_DIVIDER_NONE : SHOW_DIVIDER_MIDDLE);
        source.setVisibility(chips && !items.isEmpty() ? VISIBLE : GONE);
        for (int i = 0; i < slots.length; i++) {
            boolean filled = i < items.size();
            boolean message = newKind == Kind.NOTICE || newKind == Kind.STATUS;
            boolean noticeText = message && i == 0;
            slots[i].setText(filled ? items.get(i) : "");
            slots[i].setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    chips ? emojiSize * 0.9f : noticeText ? wordSize * 0.9f : wordSize);
            // In a notice, the two actions are tinted so they read as buttons.
            slots[i].setTextColor(message && i > 0 ? actionColor : plainColor);
            // A label that does nothing must not look or behave like something to tap.
            boolean label = newKind == Kind.STATUS && i == 0;
            slots[i].setClickable(!label);
            slots[i].setBackgroundResource(label ? 0 : chips ? R.drawable.bg_chip
                    : plainBackground);
            LayoutParams params = (LayoutParams) slots[i].getLayoutParams();
            params.weight = noticeText ? 2.4f : 1f;
            params.setMargins(chips ? chipMargin : 0, chips ? chipMargin : 0,
                    chips ? chipMargin : 0, chips ? chipMargin : 0);
            // Empty slots stay in the layout but are neither tappable nor announced.
            slots[i].setVisibility(filled ? VISIBLE : INVISIBLE);
        }
        requestLayout();
    }
}

// The row above the keys that offers up to three words. Always the same height, so the
// keyboard never jumps when suggestions come and go.
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

    public interface Listener {
        void onSuggestionPicked(String word);
    }

    private static final int[] SLOT_IDS = {R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3};

    private final TextView[] slots = new TextView[SLOT_IDS.length];
    private Listener listener;

    public SuggestionStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setShowDividers(SHOW_DIVIDER_MIDDLE);
        setDividerDrawable(context.getDrawable(R.drawable.strip_divider));
        setDividerPadding((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
                getResources().getDisplayMetrics()));

        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = 0; i < slots.length; i++) {
            TextView slot = new TextView(context);
            slot.setId(SLOT_IDS[i]);
            slot.setGravity(Gravity.CENTER);
            slot.setSingleLine(true);
            slot.setEllipsize(TextUtils.TruncateAt.END);
            slot.setTextColor(context.getColor(R.color.key_label_secondary));
            slot.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.suggestion_text_size));
            slot.setBackgroundResource(ripple.resourceId);
            slot.setOnClickListener(v -> {
                if (listener != null) listener.onSuggestionPicked(slot.getText().toString());
            });
            slots[i] = slot;
            addView(slot, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        setSuggestions(List.of());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSuggestions(List<String> words) {
        for (int i = 0; i < slots.length; i++) {
            boolean filled = i < words.size();
            slots[i].setText(filled ? words.get(i) : "");
            // Empty slots stay in the layout but are neither tappable nor announced.
            slots[i].setVisibility(filled ? VISIBLE : INVISIBLE);
        }
    }
}

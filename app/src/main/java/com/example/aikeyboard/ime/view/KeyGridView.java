// Draws one keyboard layer and turns touches into key events. One view for all keys, so
// touches between keys still land on the nearest one and two thumbs can overlap.
package com.example.aikeyboard.ime.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.example.aikeyboard.R;
import com.example.aikeyboard.ime.KeyboardLayout;
import com.example.aikeyboard.ime.KeyboardLayout.Key;
import com.example.aikeyboard.ime.ShiftState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class KeyGridView extends View {

    public interface Listener {
        /** A finger landed on a key: feedback only, nothing is typed yet. */
        void onPress(Key key);

        /** The key was typed. Fired repeatedly while backspace is held. */
        void onKey(Key key);

        /** The key was held still (used for space: choose another keyboard). */
        void onLongPress(Key key);
    }

    private static final long REPEAT_START_MS = 400;
    private static final long REPEAT_INTERVAL_MS = 50;
    private static final long LONG_PRESS_MS = 500;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();
    private final float rowHeight;
    private final float gapH;
    private final float gapV;
    private final float radius;
    private final float sidePadding;
    private final float letterSize;
    private final float functionSize;
    private final int letterColor;
    private final int functionColor;
    private final int pressedColor;
    private final int accentColor;
    private final int onAccentColor;
    private final int labelColor;
    private final int secondaryLabelColor;

    private List<List<Key>> rows = Collections.emptyList();
    private final List<Key> keys = new ArrayList<>();
    private final List<RectF> bounds = new ArrayList<>();

    // Each finger remembers the Key it is on (not an index: the layer can change under it).
    private final SparseArray<Key> pointerKey = new SparseArray<>();
    // Fingers whose key has already been typed (rollover, repeat or long press).
    private final SparseArray<Boolean> spent = new SparseArray<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timer;

    private Listener listener;
    private ShiftState.Mode shiftMode = ShiftState.Mode.OFF;
    private String enterGlyph = "→";
    private String enterDescription = "";
    private String spaceLabel = "";
    private float heightScale = 1f;
    private final KeyAccessibility accessibility = new KeyAccessibility();

    public KeyGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        rowHeight = getResources().getDimension(R.dimen.key_row_height);
        gapH = getResources().getDimension(R.dimen.key_gap_horizontal);
        gapV = getResources().getDimension(R.dimen.key_gap_vertical);
        radius = getResources().getDimension(R.dimen.key_corner_radius);
        sidePadding = getResources().getDimension(R.dimen.keyboard_side_padding);
        letterSize = getResources().getDimension(R.dimen.key_label_size);
        functionSize = getResources().getDimension(R.dimen.key_function_label_size);
        letterColor = context.getColor(R.color.key_letter);
        functionColor = context.getColor(R.color.key_function);
        pressedColor = context.getColor(R.color.key_pressed);
        accentColor = context.getColor(R.color.accent_blue);
        onAccentColor = context.getColor(R.color.on_accent_blue);
        labelColor = context.getColor(R.color.key_label);
        secondaryLabelColor = context.getColor(R.color.key_label_secondary);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        ViewCompat.setAccessibilityDelegate(this, accessibility);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRows(List<List<Key>> newRows) {
        if (rows == newRows) return;
        rows = newRows;
        requestLayout();
        layoutKeys();
    }

    public void setShiftMode(ShiftState.Mode mode) {
        if (shiftMode == mode) return;
        shiftMode = mode;
        accessibility.invalidateRoot();
        invalidate();
    }

    /** What the enter key shows and what TalkBack calls it (Send, Search, New line…). */
    public void setEnter(String glyph, String description) {
        enterGlyph = glyph;
        enterDescription = description;
        accessibility.invalidateRoot();
        invalidate();
    }

    public void setSpaceLabel(String label) {
        spaceLabel = label;
        invalidate();
    }

    /** Scales row height for the user's keyboard-height setting. */
    public void setHeightScale(float scale) {
        if (heightScale == scale) return;
        heightScale = scale;
        requestLayout();
    }

    /** Drops any touch in progress, e.g. when the keyboard is hidden mid-press. */
    public void cancelTouches() {
        cancelTimer();
        pointerKey.clear();
        spent.clear();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round(rows.size() * rowHeight * heightScale);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        layoutKeys();
    }

    private void layoutKeys() {
        keys.clear();
        bounds.clear();
        if (getWidth() == 0 || rows.isEmpty()) return;
        float unit = (getWidth() - 2 * sidePadding) / 10f;
        float height = getHeight() / (float) rows.size();
        float top = 0;
        for (List<Key> row : rows) {
            float rowWidth = 0;
            for (Key key : row) rowWidth += key.weight * unit;
            float left = (getWidth() - rowWidth) / 2f; // shorter rows are centred
            for (Key key : row) {
                float width = key.weight * unit;
                keys.add(key);
                bounds.add(new RectF(left, top, left + width, top + height));
                left += width;
            }
            top += height;
        }
        accessibility.invalidateRoot();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            RectF cell = bounds.get(i);
            drawRect.set(cell.left + gapH / 2, cell.top + gapV / 2,
                    cell.right - gapH / 2, cell.bottom - gapV / 2);
            boolean accent = key.code == KeyboardLayout.CODE_ENTER;
            keyPaint.setColor(isHeld(key) ? pressedColor : backgroundFor(key));
            canvas.drawRoundRect(drawRect, radius, radius, keyPaint);

            String label = displayLabel(key);
            // Word-like labels ("?123", "ABC", "English") are drawn smaller than single glyphs.
            // (U+FE0E is an invisible presentation selector and does not count as a glyph.)
            String glyphs = label.replace("︎", "");
            boolean small = glyphs.codePointCount(0, glyphs.length()) > 1;
            labelPaint.setTextSize(small ? functionSize : letterSize);
            labelPaint.setColor(accent ? onAccentColor
                    : key.isText() ? labelColor : secondaryLabelColor);
            float baseline = drawRect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2;
            canvas.drawText(label, drawRect.centerX(), baseline, labelPaint);
        }
    }

    private int backgroundFor(Key key) {
        switch (key.code) {
            case KeyboardLayout.CODE_ENTER:
                return accentColor;
            case KeyboardLayout.CODE_SHIFT:
                return shiftMode == ShiftState.Mode.OFF ? functionColor : pressedColor;
            case KeyboardLayout.CODE_TEXT:
            case KeyboardLayout.CODE_SPACE:
                return letterColor;
            default:
                return functionColor;
        }
    }

    private String displayLabel(Key key) {
        switch (key.code) {
            case KeyboardLayout.CODE_TEXT:
                return shiftMode == ShiftState.Mode.OFF
                        ? key.label : key.label.toUpperCase(Locale.ROOT);
            case KeyboardLayout.CODE_SHIFT:
                return shiftMode == ShiftState.Mode.LOCKED ? "⇪" : key.label;
            case KeyboardLayout.CODE_ENTER:
                return enterGlyph;
            case KeyboardLayout.CODE_SPACE:
                return spaceLabel;
            default:
                return key.label;
        }
    }

    private String describe(Key key) {
        switch (key.code) {
            case KeyboardLayout.CODE_TEXT:
                return displayLabel(key);
            case KeyboardLayout.CODE_SPACE:
                return getContext().getString(R.string.key_space);
            case KeyboardLayout.CODE_BACKSPACE:
                return getContext().getString(R.string.key_backspace);
            case KeyboardLayout.CODE_SHIFT:
                return getContext().getString(R.string.key_shift);
            case KeyboardLayout.CODE_SYMBOLS:
                return getContext().getString(R.string.key_symbols);
            case KeyboardLayout.CODE_MORE_SYMBOLS:
                return getContext().getString(R.string.key_more_symbols);
            case KeyboardLayout.CODE_LETTERS:
                return getContext().getString(R.string.key_letters);
            case KeyboardLayout.CODE_EMOJI:
                return getContext().getString(R.string.key_emoji);
            default:
                return enterDescription;
        }
    }

    private boolean isHeld(Key key) {
        for (int i = 0; i < pointerKey.size(); i++) {
            if (pointerKey.valueAt(i) == key) return true;
        }
        return false;
    }

    // The key under a point, or the nearest one: gaps and row margins are never dead zones.
    private int keyIndexAt(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < bounds.size(); i++) {
            RectF b = bounds.get(i);
            float dx = Math.max(0, Math.max(b.left - x, x - b.right));
            float dy = Math.max(0, Math.max(b.top - y, y - b.bottom));
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    // Accessibility clicks arrive per key through KeyAccessibility, not through performClick.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null || keys.isEmpty()) return false;
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                // Find the new finger's key first: typing the held keys may change the layer.
                Key landed = keys.get(keyIndexAt(event.getX(index), event.getY(index)));
                // Two-thumb typing: a second finger landing types what the first still holds.
                for (int i = 0; i < pointerKey.size(); i++) typeOnce(pointerKey.keyAt(i));
                press(id, landed);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointer = event.getPointerId(i);
                    Key under = keys.get(keyIndexAt(event.getX(i), event.getY(i)));
                    if (pointerKey.get(pointer) != under) {
                        // Sliding to another key ends any repeat or long press in progress.
                        cancelTimer();
                        pointerKey.put(pointer, under);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                cancelTimer();
                typeOnce(id);
                pointerKey.remove(id);
                spent.remove(id);
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelTouches();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void press(int pointer, Key key) {
        cancelTimer();
        pointerKey.put(pointer, key);
        spent.put(pointer, false);
        listener.onPress(key);
        if (key.code == KeyboardLayout.CODE_BACKSPACE) {
            typeOnce(pointer);
            timer = () -> {
                listener.onKey(key);
                // Typing the key may have cancelled the touch (e.g. the keyboard was hidden).
                if (timer != null) handler.postDelayed(timer, REPEAT_INTERVAL_MS);
            };
            handler.postDelayed(timer, REPEAT_START_MS);
        } else if (key.code == KeyboardLayout.CODE_SPACE) {
            timer = () -> {
                spent.put(pointer, true);
                listener.onLongPress(key);
            };
            handler.postDelayed(timer, LONG_PRESS_MS);
        }
    }

    // Types the pointer's key unless it has already been typed.
    private void typeOnce(int pointer) {
        Key key = pointerKey.get(pointer);
        if (key == null || Boolean.TRUE.equals(spent.get(pointer))) return;
        spent.put(pointer, true);
        listener.onKey(key);
    }

    private void cancelTimer() {
        if (timer != null) handler.removeCallbacks(timer);
        timer = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        // A repeat left running after the view is gone would keep deleting text.
        cancelTouches();
        super.onDetachedFromWindow();
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    /** Exposes each key to TalkBack (and UI tests) as its own focusable, clickable node. */
    private final class KeyAccessibility extends ExploreByTouchHelper {

        KeyAccessibility() {
            super(KeyGridView.this);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            for (int i = 0; i < bounds.size(); i++) {
                if (bounds.get(i).contains(x, y)) return i;
            }
            return INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            for (int i = 0; i < keys.size(); i++) ids.add(i);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id,
                @NonNull AccessibilityNodeInfoCompat node) {
            if (id >= keys.size()) { // the layer changed under a stale request
                node.setContentDescription("");
                node.setBoundsInParent(new Rect(0, 0, 1, 1));
                return;
            }
            Key key = keys.get(id);
            RectF b = bounds.get(id);
            node.setContentDescription(describe(key));
            node.setBoundsInParent(new Rect((int) b.left, (int) b.top, (int) b.right,
                    (int) b.bottom));
            node.setClassName(Button.class.getName());
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            if (key.code == KeyboardLayout.CODE_SHIFT) {
                int state = shiftMode == ShiftState.Mode.LOCKED ? R.string.key_shift_locked
                        : shiftMode == ShiftState.Mode.ONCE ? R.string.key_shift_on
                        : R.string.key_shift_off;
                node.setStateDescription(getContext().getString(state));
            }
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || listener == null
                    || id >= keys.size()) {
                return false;
            }
            listener.onPress(keys.get(id));
            listener.onKey(keys.get(id));
            return true;
        }
    }
}

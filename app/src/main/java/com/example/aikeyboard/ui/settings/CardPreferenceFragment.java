// A settings screen whose rows are drawn as rounded cards, one card per group, the way the
// reference design groups them. The rows themselves are ordinary preferences; only what is
// painted behind them changes. Settings, model detail and per-app switches all extend this.
package com.example.aikeyboard.ui.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aikeyboard.R;

import java.util.ArrayList;
import java.util.List;

public abstract class CardPreferenceFragment extends PreferenceFragmentCompat {

    private static final int FIRST = 1;
    private static final int LAST = 2;
    private static final int MIDDLE = 4;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView list = getListView();
        int side = dp(16);
        list.setPadding(side, list.getPaddingTop(), side, dp(24));
        list.setClipToPadding(false);
        // The cards draw their own hairlines; the library's full-width ones would cut
        // across the rounded corners.
        setDivider(null);
        list.addItemDecoration(new Cards());
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** Paints one rounded card behind each run of rows between group headings. */
    private final class Cards extends RecyclerView.ItemDecoration {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint();
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float radius = dp(16);

        Cards() {
            fill.setColor(requireContext().getColor(R.color.surface));
            line.setColor(requireContext().getColor(R.color.divider));
            line.setStrokeWidth(dp(1));
        }

        @Override
        public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                @NonNull RecyclerView.State state) {
            if (!(parent.getAdapter() instanceof PreferenceGroup.PreferencePositionCallback)
                    || getPreferenceScreen() == null) {
                return;
            }
            SparseIntArray places = new SparseIntArray();
            place(getPreferenceScreen(),
                    (PreferenceGroup.PreferencePositionCallback) parent.getAdapter(), places);
            for (int i = 0; i < parent.getChildCount(); i++) {
                View row = parent.getChildAt(i);
                int place = places.get(parent.getChildAdapterPosition(row));
                if (place == 0) continue; // a group heading: nothing behind it
                rect.set(row.getLeft(), row.getTop() + row.getTranslationY(), row.getRight(),
                        row.getBottom() + row.getTranslationY());
                float top = (place & FIRST) != 0 ? radius : 0;
                float bottom = (place & LAST) != 0 ? radius : 0;
                path.reset();
                path.addRoundRect(rect, new float[] {top, top, top, top, bottom, bottom,
                        bottom, bottom}, Path.Direction.CW);
                canvas.drawPath(path, fill);
                if ((place & FIRST) == 0) {
                    canvas.drawLine(rect.left + dp(16), rect.top, rect.right - dp(16), rect.top,
                            line);
                }
            }
        }

        // Works out, for every row's position in the list, where it sits in its card.
        private void place(PreferenceGroup group,
                PreferenceGroup.PreferencePositionCallback positions, SparseIntArray places) {
            List<Integer> run = new ArrayList<>();
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference preference = group.getPreference(i);
                if (!preference.isVisible()) continue;
                if (preference instanceof PreferenceCategory) {
                    close(run, places);
                    place((PreferenceGroup) preference, positions, places);
                } else {
                    run.add(positions.getPreferenceAdapterPosition(preference));
                }
            }
            close(run, places);
        }

        private void close(List<Integer> run, SparseIntArray places) {
            for (int i = 0; i < run.size(); i++) {
                places.put(run.get(i), MIDDLE | (i == 0 ? FIRST : 0)
                        | (i == run.size() - 1 ? LAST : 0));
            }
            run.clear();
        }
    }
}

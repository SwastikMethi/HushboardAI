// The "Try it" row on a model's detail screen: runs the real model on text the user types
// there and shows what it actually returned, or the actual error. Never a canned sample.
package com.example.aikeyboard.ui.models;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.example.aikeyboard.R;

import java.util.function.Consumer;

public final class TryItPreference extends Preference {

    /** Runs the model off the main thread and reports the text to show, on the main thread. */
    public interface Runner {
        void run(String text, Consumer<String> onResult);
    }

    private final Runner runner;
    private boolean ready;
    // Kept here because the row's views are re-bound whenever the screen refreshes.
    private String typed = "";
    private String result = "";

    public TryItPreference(Context context, Runner runner) {
        super(context);
        this.runner = runner;
        setLayoutResource(R.layout.preference_try_it);
        setKey("try_it"); // lets the screen scroll straight to this row; nothing is stored
        setSelectable(false);
        setPersistent(false);
    }

    /** Whether the model's files are installed; without them there is nothing to run. */
    public void setReady(boolean modelReady) {
        if (ready == modelReady) return;
        ready = modelReady;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        EditText input = (EditText) holder.findViewById(R.id.try_input);
        Button run = (Button) holder.findViewById(R.id.try_run);
        TextView output = (TextView) holder.findViewById(R.id.try_result);

        if (!input.getText().toString().equals(typed)) input.setText(typed);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                typed = s.toString();
            }
        });
        run.setEnabled(ready);
        output.setText(ready ? result : getContext().getString(R.string.try_needs_download));
        run.setOnClickListener(v -> {
            if (typed.trim().isEmpty()) return;
            output.setText(R.string.try_running);
            runner.run(typed, text -> {
                result = text;
                output.setText(text);
            });
        });
    }
}

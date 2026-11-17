// Tongue: guesses the language of a piece of text. The model ships inside the app (no
// download, no native library) and answers synchronously. A hint, never a translation.
package com.example.aikeyboard.ai.adapters;

import android.content.Context;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.data.AppSettings;

import ai.desertant.tongue.Detection;
import ai.desertant.tongue.Reliability;
import ai.desertant.tongue.Tongue;

public final class TongueAdapter implements ModelAdapter<String, LanguageHint> {

    private final Context app;
    private Tongue tongue;

    public TongueAdapter(Context appContext) {
        this.app = appContext;
    }

    @Override
    public String modelId() {
        return "tongue";
    }

    @Override
    public LanguageHint run(String text, AppSettings settings) {
        if (tongue == null) tongue = Tongue.bundled(app);
        Detection detection = tongue.detect(text, 3);
        // Only a confident, clear-cut answer counts as reliable. Short or mixed-script text
        // comes back as tentative or too close to call, and must not change anything.
        boolean reliable = detection.getReliability() == Reliability.CONFIDENT
                && !detection.isTooCloseToCall();
        return new LanguageHint(detection.getLanguage(), reliable);
    }

    @Override
    public void release() {
        tongue = null;
    }
}

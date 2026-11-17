// Runs one model on sample text for the "Try it" field and words the real result. It uses
// the same adapters and the same single inference thread as the keyboard, so a test run and
// live typing never use a model at the same time.
package com.example.aikeyboard.ui.models;

import android.content.Context;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.ai.NoticeText;
import com.example.aikeyboard.ai.RedactCategories;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.SuggestionPolicy;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.ArrayList;
import java.util.List;

final class ModelTryIt {

    private ModelTryIt() {}

    /** Whether this build can test the model from its detail screen. */
    static boolean supports(String modelId) {
        return "emo".equals(modelId) || "redact".equals(modelId) || "toxic".equals(modelId);
    }

    /** Blocking; call on the inference thread. Throws whatever the model threw. */
    static String run(AppContainer app, Context context, String modelId, String text)
            throws Exception {
        AppSettings settings = app.settings.latest();
        List<String> lines = new ArrayList<>();
        int nothing;
        if ("emo".equals(modelId)) {
            int limit = settings.integer(
                    AppSettings.modelSettingKey("emo", ModelRegistry.EMO_LIMIT), 3);
            lines.addAll(SuggestionPolicy.emoji(app.emo.run(text, settings), limit));
            nothing = R.string.try_nothing_emo;
            return lines.isEmpty() ? context.getString(nothing) : String.join("  ", lines);
        }
        if ("redact".equals(modelId)) {
            if (RedactCategories.labels(settings).isEmpty()) {
                return context.getString(R.string.try_nothing_redact);
            }
            for (SensitiveSpan span : app.redact.run(text, settings)) {
                lines.add(context.getString(R.string.review_line,
                        context.getString(NoticeText.categoryName(span.category)),
                        span.original, span.placeholder));
            }
            nothing = R.string.try_nothing_redact;
        } else {
            ToxicScores scores = app.toxic.run(text, settings);
            for (String tone : ToxicPolicy.flagged(scores.scores, scores.thresholds, settings)) {
                lines.add(context.getString(NoticeText.toneName(tone)));
            }
            nothing = R.string.try_nothing_toxic;
        }
        return lines.isEmpty() ? context.getString(nothing) : String.join("\n", lines);
    }
}

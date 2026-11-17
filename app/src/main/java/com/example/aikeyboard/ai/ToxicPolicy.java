// Decides which kinds of wording to flag from the tone model's scores. The alert-frequency
// setting is this app's own: it shifts the thresholds the model ships with, and the settings
// screen says so. Pure logic.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToxicPolicy {

    /** The kinds of wording the user can be alerted to, in the order they are reported. */
    public static final String[] CATEGORIES = {"hateful", "abusive", "threat"};

    private static final double FEWER_SHIFT = 0.15;
    private static final double MORE_SHIFT = -0.10;

    private ToxicPolicy() {}

    public static List<String> flagged(Map<String, Double> scores,
            Map<String, Double> modelThresholds, AppSettings settings) {
        String sensitivity = settings.string(AppSettings.modelSettingKey("toxic",
                ModelRegistry.TOXIC_SENSITIVITY), ModelRegistry.SENSITIVITY_STANDARD);
        double shift = ModelRegistry.SENSITIVITY_FEWER.equals(sensitivity) ? FEWER_SHIFT
                : ModelRegistry.SENSITIVITY_MORE.equals(sensitivity) ? MORE_SHIFT : 0;

        List<String> flagged = new ArrayList<>();
        for (String category : CATEGORIES) {
            boolean wanted = settings.bool(
                    AppSettings.modelSettingKey("toxic", "cat_" + category), true);
            Double score = scores.get(category);
            Double threshold = modelThresholds.get(category);
            if (!wanted || score == null || threshold == null) continue;
            double adjusted = Math.max(0.05, Math.min(0.95, threshold + shift));
            if (score >= adjusted) flagged.add(category);
        }
        return flagged;
    }
}

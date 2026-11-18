// What the kids' chat checks before anything is sent: unkind wording, personal details and
// nudity. The rules are fixed here and do not follow the keyboard's settings, so they cannot
// be loosened from the Settings tab. No screen code.
package com.example.aikeyboard.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.RedactionPlan;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChatChecks {

    /** What may happen to a typed message. */
    public enum Outcome {
        /** Nothing found: it may be sent. */
        CLEAR,
        /** A listed word, or wording the tone model flags: it may not be sent. */
        UNKIND,
        /** Personal details found: the sender is shown the masked version first. */
        PERSONAL,
        /** A check could not run: it is not sent. */
        UNCHECKED
    }

    public static final class TextVerdict {
        public final Outcome outcome;
        /** For {@link Outcome#PERSONAL}: the message with each detail replaced by its label. */
        public final String masked;

        TextVerdict(Outcome outcome, String masked) {
            this.outcome = outcome;
            this.masked = masked;
        }
    }

    private final ModelAdapter<String, ToxicScores> toxic;
    private final ModelAdapter<String, List<SensitiveSpan>> redact;
    private final ModelAdapter<Bitmap, Sensitivity> moderator;
    private final Set<String> blockedWords;
    private final AppSettings rules;

    /** @param blockedWords lower-case words that are never sent, whatever the model says */
    public ChatChecks(ModelAdapter<String, ToxicScores> toxic,
            ModelAdapter<String, List<SensitiveSpan>> redact,
            ModelAdapter<Bitmap, Sensitivity> moderator, Set<String> blockedWords,
            AppSettings rules) {
        this.toxic = toxic;
        this.redact = redact;
        this.moderator = moderator;
        this.blockedWords = blockedWords;
        this.rules = rules;
    }

    /** The checks over the app's own models and its built-in word list. */
    public static ChatChecks with(AppContainer app, Context context, AppSettings rules) {
        return new ChatChecks(app.toxic, app.redact, app.moderator, new HashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.chat_blocked_words))), rules);
    }

    /**
     * The settings every chat check runs under. The tone model is at its strictest. Personal
     * details cover addresses and links on top of the keyboard's defaults (email, phone,
     * payment, IDs); names stay off, or nearly every message ("hi Leo") would be stopped and
     * the card would be tapped past unread. The picture check keeps the model's own line.
     */
    public static AppSettings kidRules() {
        Map<String, Object> rules = new HashMap<>();
        rules.put(AppSettings.modelSettingKey("toxic", ModelRegistry.TOXIC_SENSITIVITY),
                ModelRegistry.SENSITIVITY_MORE);
        rules.put(AppSettings.modelSettingKey("redact", "cat_addresses"), true);
        rules.put(AppSettings.modelSettingKey("redact", "cat_online"), true);
        return new AppSettings(0, rules);
    }

    /** Checks a typed message. Blocking: called on the inference thread. */
    public TextVerdict text(String text) {
        if (hasBlockedWord(text)) return new TextVerdict(Outcome.UNKIND, null);
        try {
            ToxicScores tone = toxic.run(text, rules);
            if (!ToxicPolicy.flagged(tone.scores, tone.thresholds, rules).isEmpty()) {
                return new TextVerdict(Outcome.UNKIND, null);
            }
            List<SensitiveSpan> personal = redact.run(text, rules);
            if (personal.isEmpty()) return new TextVerdict(Outcome.CLEAR, null);
            return new TextVerdict(Outcome.PERSONAL, RedactionPlan.maskedText(text, personal));
        } catch (Exception e) {
            // A model that is missing, failed or timed out: what was not checked is not sent.
            return new TextVerdict(Outcome.UNCHECKED, null);
        }
    }

    /** Whether a picture may be sent. Blocking: called on the inference thread. */
    public boolean pictureAllowed(Bitmap picture) {
        try {
            return !moderator.run(picture, rules).flagged;
        } catch (Exception e) {
            return false;
        }
    }

    // ponytail: whole words in their exact spelling only, so "sh1t" or a new form of a word
    // gets past the list and is left to the tone model. Add spellings to the list, or
    // normalise look-alike characters here, if that proves too weak.
    private boolean hasBlockedWord(String text) {
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (blockedWords.contains(word)) return true;
        }
        return false;
    }
}

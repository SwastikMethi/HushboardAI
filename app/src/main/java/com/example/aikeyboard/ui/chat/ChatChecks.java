// What the kids' chat checks before anything is sent: unkind wording, a language it cannot
// read, personal details and nudity. The rules are fixed here and do not follow the keyboard's settings, so they cannot
// be loosened from the Settings tab. No screen code.
package com.example.aikeyboard.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChatChecks {

    /**
     * The longest message that may be sent, in characters. Both text models were tried with
     * the unkind words, or the email address, at the very end of a friendly message of up to
     * 4,000 characters and still found them; the limit stays well inside that.
     */
    public static final int MESSAGE_LIMIT = 1000;

    // The word that stands in for a hidden detail, by the kind of detail. Words a child
    // knows; the message carries them in square brackets and the screen draws those as
    // lock chips.
    private static final Map<String, String> HIDDEN_WORDS = Map.of(
            "email", "email", "phone", "phone number", "payment", "bank number",
            "ids", "ID number", "names", "name", "addresses", "address", "online", "link");
    private static final String HIDDEN_OTHER = "hidden";
    private static final String ENGLISH = "en";

    /** What may happen to a typed message. */
    public enum Outcome {
        /** Nothing found: it may be sent. */
        CLEAR,
        /** A listed word, or wording the tone model flags: it may not be sent. */
        UNKIND,
        /** Personal details found: the sender is shown the masked version first. */
        PERSONAL,
        /** Surely not English, the only language the checks were tried on: it is not sent. */
        OTHER_LANGUAGE,
        /** A check could not run: it is not sent. */
        UNCHECKED
    }

    public static final class TextVerdict {
        public final Outcome outcome;
        /** For {@link Outcome#PERSONAL}: the message with each detail replaced by its word. */
        public final String masked;

        TextVerdict(Outcome outcome, String masked) {
            this.outcome = outcome;
            this.masked = masked;
        }
    }

    private final ModelAdapter<String, ToxicScores> toxic;
    private final ModelAdapter<String, List<SensitiveSpan>> redact;
    private final ModelAdapter<Bitmap, Sensitivity> moderator;
    private final ModelAdapter<String, LanguageHint> tongue;
    private final Set<String> blockedWords;
    private final AppSettings rules;

    /** @param blockedWords lower-case words that are never sent, whatever the model says */
    public ChatChecks(ModelAdapter<String, ToxicScores> toxic,
            ModelAdapter<String, List<SensitiveSpan>> redact,
            ModelAdapter<Bitmap, Sensitivity> moderator,
            ModelAdapter<String, LanguageHint> tongue, Set<String> blockedWords,
            AppSettings rules) {
        this.toxic = toxic;
        this.redact = redact;
        this.moderator = moderator;
        this.tongue = tongue;
        this.blockedWords = blockedWords;
        this.rules = rules;
    }

    /** The checks over the app's own models and its built-in word list. */
    public static ChatChecks with(AppContainer app, Context context, AppSettings rules) {
        return new ChatChecks(app.toxic, app.redact, app.moderator, app.tongue,
                new HashSet<>(Arrays.asList(context.getResources()
                        .getStringArray(R.array.chat_blocked_words))), rules);
    }

    /**
     * The settings every chat check runs under. The tone model is at its strictest. Personal
     * details cover addresses and links on top of the keyboard's defaults (email, phone,
     * payment, IDs); names stay off, or nearly every message ("hi Leo") would be stopped and
     * the card would be tapped past unread. The picture check keeps the model's own line.
     * Up to four emoji ideas are asked for, one more than the keyboard's default.
     */
    public static AppSettings kidRules() {
        Map<String, Object> rules = new HashMap<>();
        rules.put(AppSettings.modelSettingKey("toxic", ModelRegistry.TOXIC_SENSITIVITY),
                ModelRegistry.SENSITIVITY_MORE);
        rules.put(AppSettings.modelSettingKey("redact", "cat_addresses"), true);
        rules.put(AppSettings.modelSettingKey("redact", "cat_online"), true);
        rules.put(AppSettings.modelSettingKey("emo", ModelRegistry.EMO_LIMIT), 4);
        return new AppSettings(0, rules);
    }

    /** Checks a typed message. Blocking: called on the inference thread. */
    public TextVerdict text(String text) {
        // Longer than anything the models were tried on: not checked, so not sent.
        if (text.length() > MESSAGE_LIMIT) return new TextVerdict(Outcome.UNCHECKED, null);
        if (hasBlockedWord(text)) return new TextVerdict(Outcome.UNKIND, null);
        try {
            // The word list and the models were only tried on English. A message the
            // language model is sure is in another language cannot be vouched for; a mere
            // guess, as on short or mixed text, changes nothing.
            LanguageHint language = tongue.run(text, rules);
            if (language.reliable && !ENGLISH.equals(language.language)) {
                return new TextVerdict(Outcome.OTHER_LANGUAGE, null);
            }
            ToxicScores tone = toxic.run(text, rules);
            if (!ToxicPolicy.flagged(tone.scores, tone.thresholds, rules).isEmpty()) {
                return new TextVerdict(Outcome.UNKIND, null);
            }
            List<SensitiveSpan> personal = redact.run(text, rules);
            if (personal.isEmpty()) return new TextVerdict(Outcome.CLEAR, null);
            return new TextVerdict(Outcome.PERSONAL, hide(text, personal));
        } catch (Exception e) {
            // A model that is missing, failed or timed out: what was not checked is not sent.
            return new TextVerdict(Outcome.UNCHECKED, null);
        }
    }

    /** Every bracketed word that can stand in for a hidden detail, such as "[email]". */
    public static List<String> hiddenWords() {
        List<String> words = new ArrayList<>();
        for (String word : HIDDEN_WORDS.values()) words.add("[" + word + "]");
        words.add("[" + HIDDEN_OTHER + "]");
        return words;
    }

    // The message with each personal detail replaced by its word in brackets.
    private static String hide(String text, List<SensitiveSpan> found) {
        List<SensitiveSpan> inOrder = new ArrayList<>(found);
        inOrder.sort(Comparator.comparingInt((SensitiveSpan span) -> span.start));
        StringBuilder safe = new StringBuilder();
        int done = 0;
        String last = null;
        for (SensitiveSpan span : inOrder) {
            if (span.start < done) continue; // inside a detail that is already hidden
            String word = "[" + HIDDEN_WORDS.getOrDefault(span.category, HIDDEN_OTHER) + "]";
            String between = text.substring(done, span.start);
            done = span.end;
            // A street and its number are one address to a child: neighbours of one kind
            // share one word.
            if (word.equals(last) && between.matches("[\\s,]*")) continue;
            safe.append(between).append(word);
            last = word;
        }
        return safe.append(text.substring(done)).toString();
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

// What the kids' chat checks before anything is sent: unkind wording, a language it cannot
// read, personal details and nudity. It also looks at a photo for people and for a few
// words to say about it, which decides nothing by itself. The rules are fixed here and do not follow the keyboard's settings, so they cannot
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
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.media.Vision.Tag;
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
    /** The one language the word list and the models were tried on. */
    static final String ENGLISH = "en";
    private static final int SEEN_WORDS = 3;

    /** What may happen to a typed message. */
    public enum Outcome {
        /** Nothing found: it may be sent. */
        CLEAR,
        /** A listed word, or wording the tone model flags: it may not be sent. */
        UNKIND,
        /** Personal details found: the sender is shown the masked version first. */
        PERSONAL,
        /** In another script and surely not English, the only language tried: it is not sent. */
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
    private final ModelAdapter<Bitmap, ImageFindings> eye;
    private final Map<String, String> namedThings;
    private final Set<String> blockedWords;
    private final AppSettings rules;

    /**
     * @param blockedWords lower-case words that are never sent, whatever the model says
     * @param namedThings the things that may be named under a photo, by the model's label
     */
    public ChatChecks(ModelAdapter<String, ToxicScores> toxic,
            ModelAdapter<String, List<SensitiveSpan>> redact,
            ModelAdapter<Bitmap, Sensitivity> moderator,
            ModelAdapter<String, LanguageHint> tongue,
            ModelAdapter<Bitmap, ImageFindings> eye, Set<String> blockedWords,
            Map<String, String> namedThings, AppSettings rules) {
        this.toxic = toxic;
        this.redact = redact;
        this.moderator = moderator;
        this.tongue = tongue;
        this.eye = eye;
        this.namedThings = namedThings;
        this.blockedWords = blockedWords;
        this.rules = rules;
    }

    /** The checks over the app's own models and its built-in word list. */
    public static ChatChecks with(AppContainer app, Context context, AppSettings rules) {
        return new ChatChecks(app.toxic, app.redact, app.moderator, app.tongue, app.eye,
                new HashSet<>(Arrays.asList(context.getResources()
                        .getStringArray(R.array.chat_blocked_words))),
                namedThings(context), rules);
    }

    /**
     * The things that may be named under a photo, by the picture model's label for each.
     * An entry of the list is "label", or "label=word" where a child's word differs.
     */
    public static Map<String, String> namedThings(Context context) {
        Map<String, String> things = new HashMap<>();
        for (String entry : context.getResources().getStringArray(R.array.chat_seen_things)) {
            int at = entry.indexOf('=');
            String label = at < 0 ? entry : entry.substring(0, at);
            things.put(label, at < 0 ? entry.replace('_', ' ') : entry.substring(at + 1));
        }
        return things;
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
            // guess, as on short or mixed text, changes nothing. In Latin letters its word
            // is not taken: there it is sure of a wrong language for about one ordinary
            // English message in seventy and one slangy one in six ("Yes! I love football"
            // is Catalan to it), and nothing in its answer tells those from real ones.
            LanguageHint language = tongue.run(text, rules);
            if (language.reliable && !ENGLISH.equals(language.language)
                    && !inLatinLetters(text)) {
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

    /** What the picture model sees in a photo: whether a person is in it, and a few words. */
    public static final class Seen {
        public final boolean person;
        /** Up to three things in the photo, surest first, in plain words. */
        public final List<String> words;

        Seen(boolean person, List<String> words) {
            this.person = person;
            this.words = words;
        }
    }

    /**
     * Looks at a photo that has passed the nudity check. This is a helper, not a check: it
     * never refuses anything, and without the picture model, or if it fails, nothing is seen
     * and nobody is assumed to be in the photo. Blocking: called on the inference thread.
     */
    public Seen look(Bitmap photo) {
        try {
            ImageFindings found = eye.run(photo, rules);
            List<String> words = new ArrayList<>();
            // The model's labels come surest first. Most are not for a child to read or are
            // not things at all, so only those on the chat's own list are named.
            for (Tag tag : found.tags) {
                if (words.size() == SEEN_WORDS) break;
                String thing = namedThings.get(tag.name);
                if (thing != null && !words.contains(thing)) words.add(thing);
            }
            return new Seen(found.people > 0 || found.faces > 0, words);
        } catch (Exception e) {
            return new Seen(false, List.of());
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

    // Whether every letter of the message is a Latin one, as English is written. Emoji,
    // digits and punctuation are not letters.
    private static boolean inLatinLetters(String text) {
        return text.codePoints().filter(Character::isLetter)
                .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
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

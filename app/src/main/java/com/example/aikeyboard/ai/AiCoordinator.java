// Decides when a model looks at a draft and whether its answer may be shown. Plain rules,
// no model deciding about models: a result is shown only if it was computed from the exact
// draft and settings that are current when it arrives. All methods run on the main thread;
// only the scheduled work runs elsewhere.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.TopicTag;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class AiCoordinator {

    /** Debounced hand-off to the inference thread; a newer request for a key replaces it. */
    public interface Scheduler {
        void schedule(String key, long delayMs, Runnable task);

        void cancelAll();
    }

    public interface Listener {
        /** What to offer for the current draft: emoji (possibly none) and at most one notice. */
        void onSuggestions(List<String> emoji, Notice notice);

        /** True when AI is on but a chosen model has no files yet. */
        void onSetupNeeded(boolean needed);

        /**
         * The language hint for the current draft (null when off or not computed yet), and
         * whether the privacy check was skipped because it does not cover that language.
         */
        void onLanguage(LanguageHint hint, boolean redactSkipped);

        /** Topics the user asked for, for the draft they asked about. */
        void onTopics(DraftSnapshot draft, List<TopicTag> topics);
    }

    private static final String EMO = "emo";
    private static final String REDACT = "redact";
    private static final String TOXIC = "toxic";
    private static final String TONGUE = "tongue";
    private static final String GIST = "gist";
    private static final String[] AUTOMATIC = {EMO, REDACT, TOXIC, TONGUE};
    // Pauses after the last keystroke before each model looks. Starting values, to be tuned
    // on real devices; the suggestion-frequency setting scales them.
    private static final long EMO_PAUSE_MS = 150;
    private static final long REDACT_PAUSE_MS = 400;
    private static final long TOXIC_PAUSE_MS = 450;
    private static final long TONGUE_PAUSE_MS = 400;
    private static final int EMO_CONTEXT_CHARS = 200;

    /** What the privacy check came back with. */
    private static final class RedactOutcome {
        final List<SensitiveSpan> spans;
        /** True when the check was not run because the language is outside its coverage. */
        final boolean skippedForLanguage;

        RedactOutcome(List<SensitiveSpan> spans, boolean skippedForLanguage) {
            this.spans = spans;
            this.skippedForLanguage = skippedForLanguage;
        }
    }

    private final Scheduler scheduler;
    private final Executor main;
    private final ModelAdapter<String, List<EmojiCandidate>> emo;
    private final ModelAdapter<String, List<SensitiveSpan>> redact;
    private final ModelAdapter<String, ToxicScores> toxic;
    private final ModelAdapter<String, LanguageHint> tongue;
    private final ModelAdapter<String, List<TopicTag>> gist;
    private final Predicate<String> modelReady;
    private final Listener listener;

    private AppSettings settings = new AppSettings(0, Map.of());
    private DraftSnapshot current;
    private Verdict verdict = Verdict.AI_OFF;

    // What the models said about the current draft. Cleared whenever the draft changes.
    private List<EmojiCandidate> emojiFound = List.of();
    private List<SensitiveSpan> spansFound = List.of();
    private List<String> toneFound = List.of();
    private LanguageHint languageFound;
    private boolean redactSkipped;
    // The draft text each kind of notice was dismissed for; it stays away until that changes.
    private String privacyDismissedFor;
    private String toneDismissedFor;

    public AiCoordinator(Scheduler scheduler, Executor main,
            ModelAdapter<String, List<EmojiCandidate>> emo,
            ModelAdapter<String, List<SensitiveSpan>> redact,
            ModelAdapter<String, ToxicScores> toxic,
            ModelAdapter<String, LanguageHint> tongue,
            ModelAdapter<String, List<TopicTag>> gist,
            Predicate<String> modelReady, Listener listener) {
        this.scheduler = scheduler;
        this.main = main;
        this.emo = emo;
        this.redact = redact;
        this.toxic = toxic;
        this.tongue = tongue;
        this.gist = gist;
        this.modelReady = modelReady;
        this.listener = listener;
    }

    /** Saved preferences changed. Anything computed under the old ones is now stale. */
    public void onSettings(AppSettings latest) {
        settings = latest;
        scheduler.cancelAll();
        forgetResults();
        publish();
        if (current != null) analyse();
    }

    /** The field's text or cursor changed (or a field was just entered). */
    public void onDraft(DraftSnapshot snapshot, Verdict fieldVerdict) {
        current = snapshot;
        verdict = fieldVerdict;
        // What is on screen was for the previous draft.
        forgetResults();
        publish();
        analyse();
    }

    /** The field lost the keyboard. Late results for it will find nothing to match. */
    public void onSessionEnded() {
        current = null;
        scheduler.cancelAll();
        forgetResults();
        privacyDismissedFor = null;
        toneDismissedFor = null;
        publish();
        listener.onSetupNeeded(false);
    }

    /** The user waved the current notice away. It stays away until the text changes. */
    public void dismissNotice() {
        Notice shown = notice();
        if (shown == null) return;
        if (shown.kind == Notice.Kind.PRIVACY) privacyDismissedFor = current.text;
        else toneDismissedFor = current.text;
        publish();
    }

    /**
     * The user asked for topics for the draft as it is now. This is the only way the topic
     * model ever runs: typing alone never triggers it.
     */
    public void requestTopics() {
        if (current == null || verdict != Verdict.ALLOWED || !usable(GIST)) return;
        if (current.text.trim().isEmpty()) return;
        DraftSnapshot forDraft = current;
        String text = current.text;
        run(GIST, 0, () -> gist.run(text, settings), List.<TopicTag>of(),
                topics -> listener.onTopics(forDraft, topics));
    }

    private void forgetResults() {
        emojiFound = List.of();
        spansFound = List.of();
        toneFound = List.of();
        languageFound = null;
        redactSkipped = false;
    }

    private Notice notice() {
        if (current == null) return null;
        return SuggestionPolicy.notice(current, spansFound, toneFound,
                current.text.equals(privacyDismissedFor), current.text.equals(toneDismissedFor));
    }

    private void publish() {
        int limit = settings.integer(
                AppSettings.modelSettingKey(EMO, ModelRegistry.EMO_LIMIT), 3);
        listener.onSuggestions(SuggestionPolicy.emoji(emojiFound, limit), notice());
        listener.onLanguage(languageFound, redactSkipped);
    }

    private void analyse() {
        if (verdict != Verdict.ALLOWED) {
            scheduler.cancelAll();
            listener.onSetupNeeded(false);
            return;
        }
        boolean missing = settings.modelActive(GIST) && !modelReady.test(GIST);
        for (String id : AUTOMATIC) {
            missing |= settings.modelActive(id) && !modelReady.test(id);
        }
        listener.onSetupNeeded(missing);
        String text = current.text;
        if (text.trim().isEmpty()) return;
        AppSettings under = settings;
        boolean languageHints = usable(TONGUE);

        if (usable(EMO)) {
            // Emoji fit the last thing said, so only recent text before the cursor is used.
            String before = current.textBeforeCursor();
            String recent = before.substring(Math.max(0, before.length() - EMO_CONTEXT_CHARS));
            if (!recent.trim().isEmpty()) {
                run(EMO, EMO_PAUSE_MS, () -> emo.run(recent, under), List.<EmojiCandidate>of(),
                        found -> emojiFound = found);
            }
        }
        if (languageHints) {
            run(TONGUE, TONGUE_PAUSE_MS, () -> tongue.run(text, under), (LanguageHint) null,
                    hint -> languageFound = hint);
        }
        if (usable(REDACT)) {
            run(REDACT, REDACT_PAUSE_MS, () -> {
                // A confident hint that the text is in a language the privacy check does
                // not cover means: do not run it, and say so. An unsure hint changes nothing.
                LanguageHint hint = languageHints ? tongue.run(text, under) : null;
                if (hint != null && hint.reliable
                        && !RedactCategories.coversLanguage(hint.language)) {
                    return new RedactOutcome(List.of(), true);
                }
                return new RedactOutcome(redact.run(text, under), false);
            }, new RedactOutcome(List.of(), false), outcome -> {
                spansFound = outcome.spans;
                redactSkipped = outcome.skippedForLanguage;
            });
        }
        if (usable(TOXIC)) {
            run(TOXIC, TOXIC_PAUSE_MS, () -> toxic.run(text, under), (ToxicScores) null,
                    scores -> toneFound = scores == null ? List.of()
                            : ToxicPolicy.flagged(scores.scores, scores.thresholds, settings));
        }
    }

    // Chosen, not paused, and with verified files. A model without files is never called:
    // calling it would let the vendor SDK start a download.
    private boolean usable(String modelId) {
        return settings.modelActive(modelId) && modelReady.test(modelId);
    }

    private <O> void run(String key, long pauseMs, Callable<O> work, O onFailure,
            Consumer<O> keep) {
        DraftSnapshot forDraft = current;
        AppSettings underSettings = settings;
        long pause = (long) (pauseMs * settings.debounceScale());
        scheduler.schedule(key, pause, () -> {
            O result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = onFailure; // a failing model shows nothing; typing is unaffected
            }
            O answer = result;
            main.execute(() -> {
                // The inference may have finished long after the user moved on.
                if (!forDraft.sameMoment(current)
                        || underSettings.revision != settings.revision) {
                    return;
                }
                keep.accept(answer);
                publish();
            });
        });
    }
}

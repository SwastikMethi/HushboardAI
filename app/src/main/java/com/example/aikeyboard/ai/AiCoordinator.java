// Decides when a model looks at a draft and whether its answer may be shown. Plain rules,
// no model deciding about models: a result is shown only if it was computed from the exact
// draft and settings that are current when it arrives. All methods run on the main thread;
// only the scheduled work runs elsewhere.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.List;
import java.util.Map;
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
    }

    private static final String EMO = "emo";
    private static final String REDACT = "redact";
    private static final String TOXIC = "toxic";
    // Pauses after the last keystroke before each model looks. Starting values, to be tuned
    // on real devices; the suggestion-frequency setting scales them.
    private static final long EMO_PAUSE_MS = 150;
    private static final long REDACT_PAUSE_MS = 400;
    private static final long TOXIC_PAUSE_MS = 450;
    private static final int EMO_CONTEXT_CHARS = 200;

    private final Scheduler scheduler;
    private final Executor main;
    private final ModelAdapter<String, List<EmojiCandidate>> emo;
    private final ModelAdapter<String, List<SensitiveSpan>> redact;
    private final ModelAdapter<String, ToxicScores> toxic;
    private final Predicate<String> modelReady;
    private final Listener listener;

    private AppSettings settings = new AppSettings(0, Map.of());
    private DraftSnapshot current;
    private Verdict verdict = Verdict.AI_OFF;

    // What the models said about the current draft. Cleared whenever the draft changes.
    private List<EmojiCandidate> emojiFound = List.of();
    private List<SensitiveSpan> spansFound = List.of();
    private List<String> toneFound = List.of();
    // The draft text each kind of notice was dismissed for; it stays away until that changes.
    private String privacyDismissedFor;
    private String toneDismissedFor;

    public AiCoordinator(Scheduler scheduler, Executor main,
            ModelAdapter<String, List<EmojiCandidate>> emo,
            ModelAdapter<String, List<SensitiveSpan>> redact,
            ModelAdapter<String, ToxicScores> toxic,
            Predicate<String> modelReady, Listener listener) {
        this.scheduler = scheduler;
        this.main = main;
        this.emo = emo;
        this.redact = redact;
        this.toxic = toxic;
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

    private void forgetResults() {
        emojiFound = List.of();
        spansFound = List.of();
        toneFound = List.of();
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
    }

    private void analyse() {
        if (verdict != Verdict.ALLOWED) {
            scheduler.cancelAll();
            listener.onSetupNeeded(false);
            return;
        }
        boolean missing = false;
        for (String id : new String[] {EMO, REDACT, TOXIC}) {
            missing |= settings.modelActive(id) && !modelReady.test(id);
        }
        listener.onSetupNeeded(missing);
        if (current.text.trim().isEmpty()) return;

        if (usable(EMO)) {
            // Emoji fit the last thing said, so only recent text before the cursor is used.
            String before = current.textBeforeCursor();
            String recent = before.substring(Math.max(0, before.length() - EMO_CONTEXT_CHARS));
            if (!recent.trim().isEmpty()) {
                run(EMO, EMO_PAUSE_MS, emo, recent, List.of(), found -> emojiFound = found);
            }
        }
        if (usable(REDACT)) {
            run(REDACT, REDACT_PAUSE_MS, redact, current.text, List.of(),
                    found -> spansFound = found);
        }
        if (usable(TOXIC)) {
            run(TOXIC, TOXIC_PAUSE_MS, toxic, current.text, null, scores -> toneFound =
                    scores == null ? List.of()
                            : ToxicPolicy.flagged(scores.scores, scores.thresholds, settings));
        }
    }

    // Chosen, not paused, and with verified files. A model without files is never called:
    // calling it would let the vendor SDK start a download.
    private boolean usable(String modelId) {
        return settings.modelActive(modelId) && modelReady.test(modelId);
    }

    private <O> void run(String modelId, long pauseMs, ModelAdapter<String, O> adapter,
            String text, O onFailure, Consumer<O> keep) {
        DraftSnapshot forDraft = current;
        AppSettings underSettings = settings;
        long pause = (long) (pauseMs * settings.debounceScale());
        scheduler.schedule(modelId, pause, () -> {
            O result;
            try {
                result = adapter.run(text, underSettings);
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

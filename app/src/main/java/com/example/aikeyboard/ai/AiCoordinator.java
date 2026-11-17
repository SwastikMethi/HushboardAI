// Decides when a model looks at a draft and whether its answer may be shown. Plain rules,
// no model deciding about models: a result is shown only if it was computed from the exact
// draft and settings that are current when it arrives. All methods run on the main thread;
// only the scheduled work runs elsewhere.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class AiCoordinator {

    /** Debounced hand-off to the inference thread; a newer request for a key replaces it. */
    public interface Scheduler {
        void schedule(String key, long delayMs, Runnable task);

        void cancelAll();
    }

    public interface Listener {
        /** Emoji to offer for the current draft; empty to clear. */
        void onEmojiSuggestions(List<String> emoji);

        /** True when AI is on but a chosen model has no files yet. */
        void onSetupNeeded(boolean needed);
    }

    private static final String EMO = "emo";
    private static final long EMO_DEBOUNCE_MS = 150;
    private static final int EMO_CONTEXT_CHARS = 200;

    private final Scheduler scheduler;
    private final Executor main;
    private final ModelAdapter<String, List<EmojiCandidate>> emo;
    private final Predicate<String> modelReady;
    private final Listener listener;

    private AppSettings settings = new AppSettings(0, Map.of());
    private DraftSnapshot current;
    private Verdict verdict = Verdict.AI_OFF;

    public AiCoordinator(Scheduler scheduler, Executor main,
            ModelAdapter<String, List<EmojiCandidate>> emo, Predicate<String> modelReady,
            Listener listener) {
        this.scheduler = scheduler;
        this.main = main;
        this.emo = emo;
        this.modelReady = modelReady;
        this.listener = listener;
    }

    /** Saved preferences changed. Anything computed under the old ones is now stale. */
    public void onSettings(AppSettings latest) {
        settings = latest;
        scheduler.cancelAll();
        listener.onEmojiSuggestions(List.of());
        if (current != null) analyse();
    }

    /** The field's text or cursor changed (or a field was just entered). */
    public void onDraft(DraftSnapshot snapshot, Verdict fieldVerdict) {
        current = snapshot;
        verdict = fieldVerdict;
        // What is on screen was for the previous draft.
        listener.onEmojiSuggestions(List.of());
        analyse();
    }

    /** The field lost the keyboard. Late results for it will find nothing to match. */
    public void onSessionEnded() {
        current = null;
        scheduler.cancelAll();
        listener.onEmojiSuggestions(List.of());
        listener.onSetupNeeded(false);
    }

    private void analyse() {
        if (verdict != Verdict.ALLOWED) {
            scheduler.cancelAll();
            listener.onSetupNeeded(false);
            return;
        }
        boolean wanted = settings.modelActive(EMO);
        boolean ready = wanted && modelReady.test(EMO);
        listener.onSetupNeeded(wanted && !ready);
        // A model without verified files is never called: calling it would start a download.
        if (!ready) return;

        String before = current.textBeforeCursor();
        String text = before.substring(Math.max(0, before.length() - EMO_CONTEXT_CHARS));
        if (text.trim().isEmpty()) return;

        DraftSnapshot forDraft = current;
        AppSettings underSettings = settings;
        long pause = (long) (EMO_DEBOUNCE_MS * settings.debounceScale());
        scheduler.schedule(EMO, pause, () -> {
            List<EmojiCandidate> result;
            try {
                result = emo.run(text, underSettings);
            } catch (Exception e) {
                result = List.of(); // a failing model shows nothing; typing is unaffected
            }
            List<EmojiCandidate> candidates = result;
            main.execute(() -> deliver(forDraft, underSettings, candidates));
        });
    }

    private void deliver(DraftSnapshot forDraft, AppSettings underSettings,
            List<EmojiCandidate> candidates) {
        // The inference may have finished long after the user moved on.
        if (!forDraft.sameMoment(current) || underSettings.revision != settings.revision) return;
        int limit = settings.integer(
                AppSettings.modelSettingKey(EMO, ModelRegistry.EMO_LIMIT), 3);
        listener.onEmojiSuggestions(SuggestionPolicy.emoji(candidates, limit));
    }
}

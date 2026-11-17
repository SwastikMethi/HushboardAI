// The coordinator's guarantees, driven by hand: a result is only ever shown for the exact
// draft and settings it was computed from, and a model is only called where it is allowed.
// The emoji "model" here is a test fake; product code never fabricates results.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AiCoordinatorTest {

    /** Captures scheduled work so a test decides when (and whether) it runs. */
    private static final class ManualScheduler implements AiCoordinator.Scheduler {
        final List<Runnable> scheduled = new ArrayList<>();
        int cancelAllCalls;

        @Override
        public void schedule(String key, long delayMs, Runnable task) {
            scheduled.add(task);
        }

        @Override
        public void cancelAll() {
            cancelAllCalls++;
        }

        Runnable last() {
            return scheduled.get(scheduled.size() - 1);
        }
    }

    private static final class FakeEmo implements ModelAdapter<String, List<EmojiCandidate>> {
        final List<String> inputs = new ArrayList<>();
        boolean fail;

        @Override
        public String modelId() {
            return "emo";
        }

        @Override
        public List<EmojiCandidate> run(String text, AppSettings settings) throws Exception {
            inputs.add(text);
            if (fail) throw new Exception("model failed");
            return List.of(new EmojiCandidate(text.contains("pizza") ? "🍕" : "👍", 0.9));
        }

        @Override
        public void release() {
        }
    }

    private static final class RecordingListener implements AiCoordinator.Listener {
        List<String> emoji = List.of();
        boolean setupNeeded;
        int updates;

        @Override
        public void onEmojiSuggestions(List<String> suggestions) {
            emoji = suggestions;
            updates++;
        }

        @Override
        public void onSetupNeeded(boolean needed) {
            setupNeeded = needed;
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakeEmo emo = new FakeEmo();
    private final RecordingListener listener = new RecordingListener();
    private boolean emoReady = true;
    private long revision = 1;
    private AiCoordinator coordinator;

    private AppSettings settings(boolean aiOn, boolean emoOn) {
        Map<String, Object> values = new HashMap<>();
        values.put(AppSettings.AI_ENABLED, aiOn);
        values.put(AppSettings.modelEnabledKey("emo"), emoOn);
        return new AppSettings(revision++, values);
    }

    private static DraftSnapshot draft(int generation, String text) {
        return new DraftSnapshot(1, generation, "com.chat.app", text, text.length(),
                text.length(), 0, false);
    }

    @Before
    public void setUp() {
        coordinator = new AiCoordinator(scheduler, Runnable::run, emo, id -> emoReady, listener);
        coordinator.onSettings(settings(true, true));
    }

    @Test
    public void showsEmojiForTheCurrentDraft() {
        coordinator.onDraft(draft(1, "pizza sounds good"), Verdict.ALLOWED);
        scheduler.last().run();
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void aResultForAnOlderDraftIsDropped() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        Runnable forOldDraft = scheduler.last();
        coordinator.onDraft(draft(2, "pizza? no, salad"), Verdict.ALLOWED);

        forOldDraft.run(); // finishes late, after the user typed more

        assertEquals("the stale result must not be shown", List.of(), listener.emoji);
        scheduler.last().run();
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void aNewDraftClearsWhatWasShownForTheOldOne() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        scheduler.last().run();
        assertEquals(List.of("🍕"), listener.emoji);

        coordinator.onDraft(draft(2, "pizza and"), Verdict.ALLOWED);

        assertEquals(List.of(), listener.emoji);
    }

    @Test
    public void aResultComputedUnderOlderSettingsIsDropped() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        Runnable underOldSettings = scheduler.last();
        coordinator.onSettings(settings(true, true)); // any saved change bumps the revision

        underOldSettings.run();

        assertEquals(List.of(), listener.emoji);
        scheduler.last().run(); // the draft is analysed again under the new settings
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void turningAiOffClearsSuggestionsAndCancelsPendingWork() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        scheduler.last().run();
        int cancelsBefore = scheduler.cancelAllCalls;
        int scheduledBefore = scheduler.scheduled.size();

        coordinator.onSettings(settings(false, true));

        assertEquals(List.of(), listener.emoji);
        assertTrue(scheduler.cancelAllCalls > cancelsBefore);
        coordinator.onDraft(draft(2, "pizza again"), Verdict.AI_OFF);
        assertEquals("nothing may be scheduled while paused",
                scheduledBefore, scheduler.scheduled.size());
    }

    @Test
    public void aModelTheUserSwitchedOffIsNotCalled() {
        coordinator.onSettings(settings(true, false));
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        assertTrue(scheduler.scheduled.isEmpty());
        assertTrue(emo.inputs.isEmpty());
    }

    @Test
    public void nothingRunsWhereTheFieldDoesNotAllowIt() {
        coordinator.onDraft(draft(1, "hunter2"), Verdict.PASSWORD_FIELD);
        coordinator.onDraft(draft(2, "hunter2"), Verdict.NO_LEARNING_FIELD);
        coordinator.onDraft(draft(3, "hunter2"), Verdict.APP_BLOCKED);
        assertTrue(scheduler.scheduled.isEmpty());
        assertTrue(emo.inputs.isEmpty());
    }

    @Test
    public void aModelThatIsNotInstalledIsNeverCalled_andSetupIsOffered() {
        emoReady = false;
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        assertTrue(scheduler.scheduled.isEmpty());
        assertTrue(emo.inputs.isEmpty());
        assertTrue(listener.setupNeeded);

        emoReady = true;
        coordinator.onDraft(draft(2, "pizza"), Verdict.ALLOWED);
        assertFalse(listener.setupNeeded);
    }

    @Test
    public void aFailingModelShowsNothingAndDoesNotThrow() {
        emo.fail = true;
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        scheduler.last().run();
        assertEquals(List.of(), listener.emoji);
    }

    @Test
    public void anEmptyDraftIsNotAnalysed() {
        coordinator.onDraft(draft(1, "   "), Verdict.ALLOWED);
        assertTrue(scheduler.scheduled.isEmpty());
    }

    @Test
    public void onlyTheTextBeforeTheCursorIsSentToTheEmojiModel() {
        coordinator.onDraft(new DraftSnapshot(1, 1, "com.chat.app", "pizza tonight", 5, 5, 0,
                false), Verdict.ALLOWED);
        scheduler.last().run();
        assertEquals(List.of("pizza"), emo.inputs);
    }

    @Test
    public void endingTheSessionClearsAndIgnoresLateResults() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        Runnable late = scheduler.last();

        coordinator.onSessionEnded();
        late.run();

        assertEquals(List.of(), listener.emoji);
    }
}

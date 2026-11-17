// The coordinator's guarantees, driven by hand: a result is only ever shown for the exact
// draft and settings it was computed from, and a model is only called where it is allowed.
// The models here are test fakes; product code never fabricates results.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.TopicTag;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AiCoordinatorTest {

    /** Captures scheduled work per model so a test decides when (and whether) it runs. */
    private static final class ManualScheduler implements AiCoordinator.Scheduler {
        final Map<String, List<Runnable>> scheduled = new HashMap<>();
        int cancelAllCalls;

        @Override
        public void schedule(String key, long delayMs, Runnable task) {
            scheduled.computeIfAbsent(key, k -> new ArrayList<>()).add(task);
        }

        @Override
        public void cancelAll() {
            cancelAllCalls++;
        }

        Runnable last(String key) {
            List<Runnable> tasks = scheduled.get(key);
            return tasks.get(tasks.size() - 1);
        }

        int count(String key) {
            List<Runnable> tasks = scheduled.get(key);
            return tasks == null ? 0 : tasks.size();
        }

        int total() {
            int total = 0;
            for (List<Runnable> tasks : scheduled.values()) total += tasks.size();
            return total;
        }

        /** Runs the newest task of every model, as the real scheduler would after a pause. */
        void runLatest() {
            for (String key : new ArrayList<>(scheduled.keySet())) last(key).run();
        }
    }

    private abstract static class Fake<O> implements ModelAdapter<String, O> {
        final String id;
        final List<String> inputs = new ArrayList<>();
        boolean fail;

        Fake(String id) {
            this.id = id;
        }

        @Override
        public String modelId() {
            return id;
        }

        @Override
        public O run(String text, AppSettings settings) throws Exception {
            inputs.add(text);
            if (fail) throw new Exception("model failed");
            return answer(text);
        }

        abstract O answer(String text);

        @Override
        public void release() {
        }
    }

    private final Fake<List<EmojiCandidate>> emo = new Fake<List<EmojiCandidate>>("emo") {
        @Override
        List<EmojiCandidate> answer(String text) {
            return List.of(new EmojiCandidate(text.contains("pizza") ? "🍕" : "👍", 0.9));
        }
    };

    private final Fake<List<SensitiveSpan>> redact = new Fake<List<SensitiveSpan>>("redact") {
        @Override
        List<SensitiveSpan> answer(String text) {
            int at = text.indexOf("anna@example.hu");
            return at < 0 ? List.of() : List.of(new SensitiveSpan("email", at, at + 15,
                    "anna@example.hu", "[EMAIL_1]"));
        }
    };

    private final Fake<ToxicScores> toxic = new Fake<ToxicScores>("toxic") {
        @Override
        ToxicScores answer(String text) {
            double score = text.contains("idiot") ? 0.9 : 0.01;
            return new ToxicScores(Map.of("abusive", score), Map.of("abusive", 0.44));
        }
    };

    private final Fake<LanguageHint> tongue = new Fake<LanguageHint>("tongue") {
        @Override
        LanguageHint answer(String text) {
            if (text.contains("こんにちは")) return new LanguageHint("ja", true);
            if (text.contains("konnichiwa")) return new LanguageHint("ja", false);
            return new LanguageHint("en", true);
        }
    };

    private final Fake<List<TopicTag>> gist = new Fake<List<TopicTag>>("gist") {
        @Override
        List<TopicTag> answer(String text) {
            return List.of(new TopicTag("food-drink", "Food & Drink", 0.8));
        }
    };

    private static final class RecordingListener implements AiCoordinator.Listener {
        List<String> emoji = List.of();
        Notice notice;
        boolean setupNeeded;
        LanguageHint language;
        boolean redactSkipped;
        List<TopicTag> topics;
        DraftSnapshot topicsFor;

        @Override
        public void onSuggestions(List<String> emojiSuggestions, Notice latestNotice) {
            emoji = emojiSuggestions;
            notice = latestNotice;
        }

        @Override
        public void onSetupNeeded(boolean needed) {
            setupNeeded = needed;
        }

        @Override
        public void onLanguage(LanguageHint hint, boolean redactSkippedForIt) {
            language = hint;
            redactSkipped = redactSkippedForIt;
        }

        @Override
        public void onTopics(DraftSnapshot draft, List<TopicTag> found) {
            topicsFor = draft;
            topics = found;
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private final RecordingListener listener = new RecordingListener();
    private final Set<String> notReady = new HashSet<>();
    private long revision = 1;
    private AiCoordinator coordinator;

    private AppSettings settings(boolean aiOn, String... enabledModels) {
        Map<String, Object> values = new HashMap<>();
        values.put(AppSettings.AI_ENABLED, aiOn);
        for (String model : enabledModels) values.put(AppSettings.modelEnabledKey(model), true);
        return new AppSettings(revision++, values);
    }

    private static DraftSnapshot draft(int generation, String text) {
        return new DraftSnapshot(1, generation, "com.chat.app", text, text.length(),
                text.length(), 0, false);
    }

    @Before
    public void setUp() {
        coordinator = new AiCoordinator(scheduler, Runnable::run, emo, redact, toxic, tongue,
                gist, id -> !notReady.contains(id), listener);
        coordinator.onSettings(settings(true, "emo", "redact", "toxic"));
    }

    // ---- Language hint ----

    @Test
    public void aConfidentLanguageRedactDoesNotCoverSkipsRedactAndSaysSo() {
        coordinator.onSettings(settings(true, "redact", "tongue"));
        coordinator.onDraft(draft(1, "こんにちは anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertTrue("Redact must not be asked about a language it does not cover",
                redact.inputs.isEmpty());
        assertNull(listener.notice);
        assertEquals("ja", listener.language.language);
        assertTrue(listener.redactSkipped);
    }

    @Test
    public void anUnsureLanguageGuessChangesNothing() {
        coordinator.onSettings(settings(true, "redact", "tongue"));
        coordinator.onDraft(draft(1, "konnichiwa anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
        assertFalse(listener.redactSkipped);
    }

    @Test
    public void aCoveredLanguageRunsRedactAsUsual() {
        coordinator.onSettings(settings(true, "redact", "tongue"));
        coordinator.onDraft(draft(1, "mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
        assertEquals("en", listener.language.language);
        assertFalse(listener.redactSkipped);
    }

    @Test
    public void withTheLanguageHintOffRedactAlwaysRuns() {
        coordinator.onSettings(settings(true, "redact"));
        coordinator.onDraft(draft(1, "こんにちは anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
        assertTrue(tongue.inputs.isEmpty());
        assertNull(listener.language);
    }

    // ---- Topics: only when asked ----

    @Test
    public void topicsAreComputedOnlyWhenTheUserAsks() {
        coordinator.onSettings(settings(true, "gist"));
        coordinator.onDraft(draft(1, "pizza and pasta tonight"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertTrue("typing alone must not run the topic model", gist.inputs.isEmpty());

        coordinator.requestTopics();
        scheduler.last("gist").run();

        assertEquals("Food & Drink", listener.topics.get(0).name);
        assertEquals(1, listener.topicsFor.generation);
    }

    @Test
    public void topicsForAnOlderDraftAreDropped() {
        coordinator.onSettings(settings(true, "gist"));
        coordinator.onDraft(draft(1, "pizza and pasta tonight"), Verdict.ALLOWED);
        coordinator.requestTopics();
        Runnable forOldDraft = scheduler.last("gist");
        coordinator.onDraft(draft(2, "actually, the weather"), Verdict.ALLOWED);

        forOldDraft.run();

        assertNull(listener.topics);
    }

    @Test
    public void topicsAreNotComputedWhereAiMayNotRunOrTheModelIsOff() {
        coordinator.onSettings(settings(true, "gist"));
        coordinator.onDraft(draft(1, "hunter2"), Verdict.PASSWORD_FIELD);
        coordinator.requestTopics();
        assertEquals(0, scheduler.count("gist"));

        coordinator.onSettings(settings(true, "emo"));
        coordinator.onDraft(draft(2, "pizza"), Verdict.ALLOWED);
        coordinator.requestTopics();
        assertEquals(0, scheduler.count("gist"));
    }

    // ---- Emoji ----

    @Test
    public void showsEmojiForTheCurrentDraft() {
        coordinator.onDraft(draft(1, "pizza sounds good"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void aResultForAnOlderDraftIsDropped() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        Runnable forOldDraft = scheduler.last("emo");
        coordinator.onDraft(draft(2, "pizza? no, salad"), Verdict.ALLOWED);

        forOldDraft.run(); // finishes late, after the user typed more

        assertEquals("the stale result must not be shown", List.of(), listener.emoji);
        scheduler.last("emo").run();
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void aNewDraftClearsWhatWasShownForTheOldOne() {
        coordinator.onDraft(draft(1, "pizza, mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(List.of("🍕"), listener.emoji);
        assertNotNull(listener.notice);

        coordinator.onDraft(draft(2, "pizza, mail anna@example.hu now"), Verdict.ALLOWED);

        assertEquals(List.of(), listener.emoji);
        assertNull(listener.notice);
    }

    @Test
    public void aResultComputedUnderOlderSettingsIsDropped() {
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        Runnable underOldSettings = scheduler.last("emo");
        coordinator.onSettings(settings(true, "emo")); // any saved change bumps the revision

        underOldSettings.run();

        assertEquals(List.of(), listener.emoji);
        scheduler.last("emo").run(); // the draft is analysed again under the new settings
        assertEquals(List.of("🍕"), listener.emoji);
    }

    @Test
    public void turningAiOffClearsEverythingAndCancelsPendingWork() {
        coordinator.onDraft(draft(1, "pizza, mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        int cancelsBefore = scheduler.cancelAllCalls;
        int scheduledBefore = scheduler.total();

        coordinator.onSettings(settings(false, "emo", "redact", "toxic"));

        assertEquals(List.of(), listener.emoji);
        assertNull(listener.notice);
        assertTrue(scheduler.cancelAllCalls > cancelsBefore);
        coordinator.onDraft(draft(2, "pizza again"), Verdict.AI_OFF);
        assertEquals("nothing may be scheduled while paused",
                scheduledBefore, scheduler.total());
    }

    @Test
    public void aModelTheUserSwitchedOffIsNotCalled() {
        coordinator.onSettings(settings(true, "emo"));
        coordinator.onDraft(draft(1, "you idiot, mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(1, scheduler.count("emo"));
        assertEquals(0, scheduler.count("redact"));
        assertEquals(0, scheduler.count("toxic"));
        assertTrue(redact.inputs.isEmpty() && toxic.inputs.isEmpty());
        assertNull(listener.notice);
    }

    @Test
    public void nothingRunsWhereTheFieldDoesNotAllowIt() {
        coordinator.onDraft(draft(1, "hunter2"), Verdict.PASSWORD_FIELD);
        coordinator.onDraft(draft(2, "hunter2"), Verdict.NO_LEARNING_FIELD);
        coordinator.onDraft(draft(3, "hunter2"), Verdict.APP_BLOCKED);
        assertEquals(0, scheduler.total());
        assertTrue(emo.inputs.isEmpty() && redact.inputs.isEmpty() && toxic.inputs.isEmpty());
    }

    @Test
    public void aModelThatIsNotInstalledIsNeverCalled_andSetupIsOffered() {
        notReady.add("emo");
        coordinator.onDraft(draft(1, "pizza"), Verdict.ALLOWED);
        assertEquals(0, scheduler.count("emo"));
        assertTrue(listener.setupNeeded);

        notReady.clear();
        coordinator.onDraft(draft(2, "pizza"), Verdict.ALLOWED);
        assertFalse(listener.setupNeeded);
    }

    @Test
    public void aFailingModelShowsNothingAndDoesNotThrow() {
        emo.fail = true;
        redact.fail = true;
        toxic.fail = true;
        coordinator.onDraft(draft(1, "pizza idiot anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(List.of(), listener.emoji);
        assertNull(listener.notice);
    }

    @Test
    public void anEmptyDraftIsNotAnalysed() {
        coordinator.onDraft(draft(1, "   "), Verdict.ALLOWED);
        assertEquals(0, scheduler.total());
    }

    @Test
    public void onlyTheTextBeforeTheCursorIsSentToTheEmojiModel() {
        coordinator.onDraft(new DraftSnapshot(1, 1, "com.chat.app", "pizza tonight", 5, 5, 0,
                false), Verdict.ALLOWED);
        scheduler.last("emo").run();
        assertEquals(List.of("pizza"), emo.inputs);
    }

    @Test
    public void endingTheSessionClearsAndIgnoresLateResults() {
        coordinator.onDraft(draft(1, "pizza, mail anna@example.hu"), Verdict.ALLOWED);
        Runnable lateEmoji = scheduler.last("emo");
        Runnable lateRedact = scheduler.last("redact");

        coordinator.onSessionEnded();
        lateEmoji.run();
        lateRedact.run();

        assertEquals(List.of(), listener.emoji);
        assertNull(listener.notice);
    }

    // ---- Notices ----

    @Test
    public void personalInformationRaisesAPrivacyNoticeForTheWholeWindow() {
        coordinator.onDraft(draft(1, "mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
        assertEquals("anna@example.hu", listener.notice.spans.get(0).original);
        assertEquals(List.of("mail anna@example.hu"), redact.inputs);
    }

    @Test
    public void aPrivacyResultForAnOlderDraftIsDropped() {
        coordinator.onDraft(draft(1, "mail anna@example.hu"), Verdict.ALLOWED);
        Runnable forOldDraft = scheduler.last("redact");
        coordinator.onDraft(draft(2, "mail me"), Verdict.ALLOWED);

        forOldDraft.run();

        assertNull("a notice for text that is gone must not appear", listener.notice);
    }

    @Test
    public void abusiveWordingRaisesAToneNoticeWhenThereIsNoPrivacyNotice() {
        coordinator.onDraft(draft(1, "you idiot"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(Notice.Kind.TONE, listener.notice.kind);
        assertEquals(List.of("abusive"), listener.notice.tone);
    }

    @Test
    public void privacyComesFirstAndDismissingItRevealsTheToneNotice() {
        coordinator.onDraft(draft(1, "you idiot, mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);

        coordinator.dismissNotice();
        assertEquals(Notice.Kind.TONE, listener.notice.kind);

        coordinator.dismissNotice();
        assertNull(listener.notice);
    }

    @Test
    public void aDismissedNoticeStaysAwayWhileTheTextIsUnchanged() {
        coordinator.onDraft(draft(1, "mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        coordinator.dismissNotice();

        // The cursor moves; the text is the same.
        coordinator.onDraft(new DraftSnapshot(1, 2, "com.chat.app", "mail anna@example.hu",
                4, 4, 0, false), Verdict.ALLOWED);
        scheduler.runLatest();

        assertNull("dismissed for this text", listener.notice);
    }

    @Test
    public void aDismissedNoticeReturnsOnceTheTextChanges() {
        coordinator.onDraft(draft(1, "mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        coordinator.dismissNotice();

        coordinator.onDraft(draft(2, "mail anna@example.hu please"), Verdict.ALLOWED);
        scheduler.runLatest();

        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
    }

    @Test
    public void emojiAndANoticeCanBothBeReported() {
        coordinator.onDraft(draft(1, "pizza, mail anna@example.hu"), Verdict.ALLOWED);
        scheduler.runLatest();
        assertEquals(List.of("🍕"), listener.emoji);
        assertEquals(Notice.Kind.PRIVACY, listener.notice.kind);
    }
}

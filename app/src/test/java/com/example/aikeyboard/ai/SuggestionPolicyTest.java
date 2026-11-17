// Turning raw model output into the small amount the keyboard shows.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SuggestionPolicyTest {

    private static EmojiCandidate candidate(String emoji, double confidence) {
        return new EmojiCandidate(emoji, confidence);
    }

    private static final DraftSnapshot DRAFT =
            new DraftSnapshot(1, 1, "com.chat.app", "mail anna@example.hu", 20, 20, 0, false);
    private static final List<SensitiveSpan> SPANS = List.of(
            new SensitiveSpan("email", 5, 20, "anna@example.hu", "[EMAIL_1]"));

    @Test
    public void emojiAreShownMostConfidentFirst() {
        List<String> shown = SuggestionPolicy.emoji(Arrays.asList(
                candidate("😋", 0.04), candidate("🍕", 0.83), candidate("🔥", 0.03)), 3);
        assertEquals(Arrays.asList("🍕", "😋", "🔥"), shown);
    }

    @Test
    public void veryUnlikelyEmojiAreLeftOut() {
        List<String> shown = SuggestionPolicy.emoji(Arrays.asList(
                candidate("🍕", 0.83), candidate("🧦", 0.001)), 3);
        assertEquals(List.of("🍕"), shown);
    }

    @Test
    public void theUsersLimitIsRespected() {
        List<String> shown = SuggestionPolicy.emoji(Arrays.asList(
                candidate("🍕", 0.5), candidate("😋", 0.3), candidate("🔥", 0.2)), 2);
        assertEquals(Arrays.asList("🍕", "😋"), shown);
    }

    @Test
    public void theSameEmojiIsNotShownTwice() {
        List<String> shown = SuggestionPolicy.emoji(Arrays.asList(
                candidate("🍕", 0.5), candidate("🍕", 0.4), candidate("😋", 0.3)), 3);
        assertEquals(Arrays.asList("🍕", "😋"), shown);
    }

    @Test
    public void noCandidatesMeansNothingToShow() {
        assertEquals(List.of(), SuggestionPolicy.emoji(List.of(), 3));
    }

    @Test
    public void aPrivacyNoticeComesBeforeAToneNotice() {
        Notice notice = SuggestionPolicy.notice(DRAFT, SPANS, List.of("abusive"), false, false);
        assertEquals(Notice.Kind.PRIVACY, notice.kind);
        assertEquals(SPANS, notice.spans);
    }

    @Test
    public void onceThePrivacyNoticeIsDismissedTheToneNoticeCanShow() {
        Notice notice = SuggestionPolicy.notice(DRAFT, SPANS, List.of("abusive"), true, false);
        assertEquals(Notice.Kind.TONE, notice.kind);
        assertEquals(List.of("abusive"), notice.tone);
    }

    @Test
    public void onlyOneNoticeAtATime_andNoneWhenBothAreDismissedOrEmpty() {
        assertNull(SuggestionPolicy.notice(DRAFT, SPANS, List.of("abusive"), true, true));
        assertNull(SuggestionPolicy.notice(DRAFT, List.of(), List.of(), false, false));
    }

    @Test
    public void aNoticeSaysWhenOnlyPartOfTheDraftWasSeen() {
        DraftSnapshot partial = new DraftSnapshot(1, 1, "com.chat.app",
                "mail anna@example.hu", 20, 20, 300, true);
        Notice notice = SuggestionPolicy.notice(partial, SPANS, List.of(), false, false);
        assertEquals(true, notice.partial);
        assertEquals(300, notice.draft.offset);
    }
}

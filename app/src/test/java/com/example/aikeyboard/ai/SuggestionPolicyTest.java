// Turning raw model output into the small amount the keyboard shows.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SuggestionPolicyTest {

    private static EmojiCandidate candidate(String emoji, double confidence) {
        return new EmojiCandidate(emoji, confidence);
    }

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
}

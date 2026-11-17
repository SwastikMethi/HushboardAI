// Turns model results into the small amount the keyboard shows. Pure logic.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SuggestionPolicy {

    /** Below this an emoji is noise rather than a suggestion. */
    static final double MIN_EMOJI_CONFIDENCE = 0.02;

    private SuggestionPolicy() {}

    /** Up to {@code limit} distinct emoji, most confident first. */
    public static List<String> emoji(List<EmojiCandidate> candidates, int limit) {
        List<EmojiCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble((EmojiCandidate c) -> c.confidence).reversed());
        List<String> shown = new ArrayList<>();
        for (EmojiCandidate candidate : ranked) {
            if (shown.size() == limit || candidate.confidence < MIN_EMOJI_CONFIDENCE) break;
            if (!shown.contains(candidate.emoji)) shown.add(candidate.emoji);
        }
        return shown;
    }

    /**
     * At most one notice at a time: personal information first, then tone. A notice the user
     * dismissed for this text is skipped. Null when there is nothing to raise.
     */
    public static Notice notice(DraftSnapshot draft, List<SensitiveSpan> spans, List<String> tone,
            boolean privacyDismissed, boolean toneDismissed) {
        if (!spans.isEmpty() && !privacyDismissed) {
            return new Notice(Notice.Kind.PRIVACY, draft, spans, List.of());
        }
        if (!tone.isEmpty() && !toneDismissed) {
            return new Notice(Notice.Kind.TONE, draft, List.of(), tone);
        }
        return null;
    }
}

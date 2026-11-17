// Value types for what models propose and what the keyboard then offers. Held in memory for
// the current field only; never stored or logged.
package com.example.aikeyboard.ai;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Suggestion {

    private Suggestion() {}

    /** One emoji the emoji model proposed, with its confidence between 0 and 1. */
    public static final class EmojiCandidate {
        public final String emoji;
        public final double confidence;

        public EmojiCandidate(String emoji, double confidence) {
            this.emoji = emoji;
            this.confidence = confidence;
        }
    }

    /** A piece of personal information found in the analysed text. */
    public static final class SensitiveSpan {
        /** The user-facing category: email, phone, payment, ids, names, addresses, online. */
        public final String category;
        /** UTF-16 range within the analysed text, on whole characters, end exclusive. */
        public final int start;
        public final int end;
        public final String original;
        /** What masking would put in its place, e.g. [EMAIL_1]. */
        public final String placeholder;

        public SensitiveSpan(String category, int start, int end, String original,
                String placeholder) {
            this.category = category;
            this.start = start;
            this.end = end;
            this.original = original;
            this.placeholder = placeholder;
        }
    }

    /** The tone model's raw output for the wording it judges: hateful, abusive, threat. */
    public static final class ToxicScores {
        public final Map<String, Double> scores;
        /** The thresholds the model itself ships with. */
        public final Map<String, Double> thresholds;

        public ToxicScores(Map<String, Double> scores, Map<String, Double> thresholds) {
            this.scores = scores;
            this.thresholds = thresholds;
        }
    }

    /** The language model's best guess for a piece of text. A hint, never a translation. */
    public static final class LanguageHint {
        /** Language code as the model reports it, e.g. "fr". */
        public final String language;
        /** False for short or mixed text, where the guess must not change any behaviour. */
        public final boolean reliable;

        public LanguageHint(String language, boolean reliable) {
            this.language = language;
            this.reliable = reliable;
        }
    }

    /** One topic from the topic model's fixed list. */
    public static final class TopicTag {
        public final String slug;
        public final String name;
        public final double score;

        public TopicTag(String slug, String name, double score) {
            this.slug = slug;
            this.name = name;
            this.score = score;
        }
    }

    /** The one thing the keyboard may draw attention to at a time. A signal, not a verdict. */
    public static final class Notice {
        public enum Kind { PRIVACY, TONE }

        public final Kind kind;
        /** The draft this notice is about; edits are checked against it. */
        public final DraftSnapshot draft;
        /** For a privacy notice: what was found. */
        public final List<SensitiveSpan> spans;
        /** For a tone notice: which kinds of wording were flagged. */
        public final List<String> tone;
        /** True when only part of the draft was visible, so nothing may claim a full check. */
        public final boolean partial;

        Notice(Kind kind, DraftSnapshot draft, List<SensitiveSpan> spans, List<String> tone) {
            this.kind = kind;
            this.draft = draft;
            this.spans = Collections.unmodifiableList(spans);
            this.tone = Collections.unmodifiableList(tone);
            this.partial = draft.partial;
        }
    }
}

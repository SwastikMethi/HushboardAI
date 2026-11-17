// Value types for what models propose, before the policy decides what is shown.
package com.example.aikeyboard.ai;

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
}

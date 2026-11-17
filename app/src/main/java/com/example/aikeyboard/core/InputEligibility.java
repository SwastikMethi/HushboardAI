// Decides whether AI may look at the field being typed in, and if not, why. Pure logic.
package com.example.aikeyboard.core;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.ime.InputSession;

public final class InputEligibility {

    public enum Verdict {
        ALLOWED,
        PASSWORD_FIELD,
        /** The field asked keyboards not to learn from it; this app leaves it alone entirely. */
        NO_LEARNING_FIELD,
        /** Not a prose field (numbers, phone, dates). */
        UNSUPPORTED_FIELD,
        /** The user turned AI off for this app. */
        APP_BLOCKED,
        AI_OFF
    }

    private InputEligibility() {}

    /**
     * Reasons that belong to the field come first, so they are reported even while AI is
     * paused: the keyboard can then say "off in password fields" rather than just "off".
     */
    public static Verdict check(AppSettings settings, InputSession session) {
        if (session.password) return Verdict.PASSWORD_FIELD;
        if (session.noLearning) return Verdict.NO_LEARNING_FIELD;
        if (!session.textField) return Verdict.UNSUPPORTED_FIELD;
        if (settings.stringSet(AppSettings.BLOCKED_APPS).contains(session.editorPackage)) {
            return Verdict.APP_BLOCKED;
        }
        return settings.aiEnabled() ? Verdict.ALLOWED : Verdict.AI_OFF;
    }
}

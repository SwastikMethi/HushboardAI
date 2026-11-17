// Where AI may run. A password or a no-learning field wins over everything else, so the
// keyboard can say why help is unavailable instead of just staying silent.
package com.example.aikeyboard.core;

import static android.text.InputType.TYPE_CLASS_NUMBER;
import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
import static android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
import static org.junit.Assert.assertEquals;

import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.ime.InputSession;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class InputEligibilityTest {

    private static AppSettings settings(boolean aiOn, String... blockedApps) {
        Map<String, Object> values = new HashMap<>();
        values.put(AppSettings.AI_ENABLED, aiOn);
        values.put(AppSettings.BLOCKED_APPS, Set.of(blockedApps));
        return new AppSettings(1, values);
    }

    private static InputSession field(int inputType, int imeOptions) {
        return new InputSession("com.chat.app", inputType, imeOptions);
    }

    @Test
    public void anOrdinaryTextFieldWithAiOnIsAllowed() {
        assertEquals(Verdict.ALLOWED,
                InputEligibility.check(settings(true), field(TYPE_CLASS_TEXT, 0)));
    }

    @Test
    public void aiOffMeansNothingRuns() {
        assertEquals(Verdict.AI_OFF,
                InputEligibility.check(settings(false), field(TYPE_CLASS_TEXT, 0)));
    }

    @Test
    public void passwordFieldsAreNeverAnalysed() {
        assertEquals(Verdict.PASSWORD_FIELD, InputEligibility.check(settings(true),
                field(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD, 0)));
    }

    @Test
    public void fieldsThatAskNotToBeLearnedFromAreLeftAlone() {
        assertEquals(Verdict.NO_LEARNING_FIELD, InputEligibility.check(settings(true),
                field(TYPE_CLASS_TEXT, IME_FLAG_NO_PERSONALIZED_LEARNING)));
    }

    @Test
    public void nonTextFieldsAreUnsupported() {
        assertEquals(Verdict.UNSUPPORTED_FIELD,
                InputEligibility.check(settings(true), field(TYPE_CLASS_NUMBER, 0)));
    }

    @Test
    public void anAppTheUserTurnedAiOffForIsBlocked() {
        assertEquals(Verdict.APP_BLOCKED, InputEligibility.check(
                settings(true, "com.chat.app"), field(TYPE_CLASS_TEXT, 0)));
        assertEquals(Verdict.ALLOWED, InputEligibility.check(
                settings(true, "com.other.app"), field(TYPE_CLASS_TEXT, 0)));
    }

    @Test
    public void theFieldsOwnReasonIsReportedEvenWhileAiIsOff() {
        assertEquals(Verdict.PASSWORD_FIELD, InputEligibility.check(settings(false),
                field(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD, 0)));
    }
}

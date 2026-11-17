// Which onboarding step the user is on, from what Android reports about keyboards.
package com.example.aikeyboard.ui.onboarding;

import static org.junit.Assert.assertEquals;

import com.example.aikeyboard.ui.onboarding.SetupFragment.Step;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SetupStepTest {

    private static final String OURS = "com.example.aikeyboard/.ime.AiInputMethodService";
    private static final String OTHER = "com.other/.Keyboard";

    @Test
    public void aKeyboardThatIsNotEnabledMustBeEnabledFirst() {
        assertEquals(Step.ENABLE, SetupFragment.step(List.of(OTHER), OTHER, OURS));
    }

    @Test
    public void anEnabledKeyboardStillHasToBeChosen() {
        assertEquals(Step.SELECT, SetupFragment.step(Arrays.asList(OTHER, OURS), OTHER, OURS));
    }

    @Test
    public void noCurrentKeyboardCountsAsNotChosen() {
        assertEquals(Step.SELECT, SetupFragment.step(List.of(OURS), null, OURS));
    }

    @Test
    public void enabledAndChosenIsDone() {
        assertEquals(Step.DONE, SetupFragment.step(Arrays.asList(OTHER, OURS), OURS, OURS));
    }
}

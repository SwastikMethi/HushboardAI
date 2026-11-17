// The onboarding screen reflects the real system state of the keyboard each time it is shown.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SetupScreenTest {

    private static final String IME_ID = "com.example.aikeyboard/.ime.AiInputMethodService";

    private UiDevice device;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(getInstrumentation());
    }

    @After
    public void tearDown() throws Exception {
        // Leave the keyboard selected for whichever test runs next.
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
    }

    @Test
    public void showsDoneWhenTheKeyboardIsEnabledAndChosen() throws Exception {
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.setup_status)).check(matches(withText(R.string.setup_status_done)));
            onView(withId(R.id.setup_practice)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void asksToEnableTheKeyboardWhenItIsOff_andNoticesWhenItComesBack() throws Exception {
        device.executeShellCommand("ime disable " + IME_ID);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.setup_status))
                    .check(matches(withText(R.string.setup_status_enable)));
            onView(withId(R.id.setup_choose)).check(matches(not(isEnabled())));

            // The user turns it on in system settings and returns: the screen must catch up.
            device.executeShellCommand("ime enable " + IME_ID);
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withId(R.id.setup_status))
                    .check(matches(withText(R.string.setup_status_select)));
            onView(withId(R.id.setup_choose)).check(matches(isEnabled()));
        }
    }
}

// The companion app's screens, driven the way a user would: tabs, the model catalog with a
// real download, the master switch, model detail, settings and the privacy disclosure.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CompanionAppTest {

    private final SettingsRepository settings = TestModels.container().settings;
    private final ModelRepository models = TestModels.container().models;
    private ActivityScenario<MainActivity> app;

    @Before
    public void setUp() {
        app = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void tearDown() {
        app.close();
    }

    private static Matcher<View> row(String modelId) {
        return withTagValue(is((Object) ("model_row_" + modelId)));
    }

    private static Matcher<View> statusOf(String modelId) {
        return allOf(withId(R.id.model_status), isDescendantOfA(row(modelId)));
    }

    private void openModels() {
        onView(withId(R.id.tab_models)).perform(click());
    }

    private boolean setting(String key) throws InterruptedException {
        return settings.latest().bool(key, false);
    }

    @Test
    public void theThreeTabsShowTheirScreens() {
        onView(withId(R.id.setup_status)).check(matches(isDisplayed()));
        openModels();
        onView(withId(R.id.models_heading)).check(matches(isDisplayed()));
        onView(withId(R.id.tab_settings)).perform(click());
        onView(withText(R.string.settings_height)).check(matches(isDisplayed()));
        onView(withId(R.id.tab_keyboard)).perform(click());
        onView(withId(R.id.setup_status)).check(matches(isDisplayed()));
    }

    @Test
    public void theCatalogListsAllThirteenModelsWithHonestStates() {
        openModels();
        for (ModelDescriptor model : ModelRegistry.all()) {
            onView(row(model.id)).perform(scrollTo())
                    .check(matches(hasDescendant(withText(model.name))));
        }
        onView(statusOf("tongue")).perform(scrollTo())
                .check(matches(withText(R.string.status_included)));
        // No adapter yet: must say so rather than offer a download that does nothing.
        onView(statusOf("voz")).perform(scrollTo())
                .check(matches(withText(R.string.status_coming_later)));
        onView(withText(R.string.models_not_on_android)).perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void downloadingFromTheCatalogEndsInReadyAndTheModelCanBeTurnedOn() throws Exception {
        assumeTrue("needs network", TestModels.online());
        models.delete("emo");
        assertTrue(TestModels.await(() -> models.bytesOnDisk("emo") == 0, 10_000));
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        openModels();

        onView(withContentDescription("Download Emo")).perform(scrollTo(), click());
        assertTrue("emo did not become ready: " + models.status("emo").state,
                TestModels.awaitStatus("emo", s -> s.state == AssetState.READY, 3 * 60_000));

        onView(withContentDescription("Use Emo")).perform(scrollTo(), click());
        assertTrue(TestModels.await(
                () -> settings.latest().modelEnabled("emo"), 5000));
        onView(statusOf("emo")).check(matches(withText(R.string.status_ready)));
    }

    @Test
    public void theMasterSwitchPausesEverythingButKeepsEachChoice() throws Exception {
        // Installing may put its own screen up, which replaces the one from setUp.
        app.close();
        TestModels.ensureInstalled("emo");
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        settings.setBoolean(AppSettings.modelEnabledKey("emo"), true);
        assertTrue(TestModels.await(() -> settings.latest().modelActive("emo"), 5000));
        app = ActivityScenario.launch(MainActivity.class);
        openModels();

        onView(withId(R.id.ai_master_switch)).perform(click());

        assertTrue(TestModels.await(() -> !settings.latest().aiEnabled(), 5000));
        assertTrue("the model choice must survive a pause",
                settings.latest().modelEnabled("emo"));
        assertFalse(settings.latest().modelActive("emo"));
        onView(statusOf("emo")).check(matches(withText(R.string.status_paused)));
    }

    @Test
    public void modelDetailShowsOnlyThatModelsRealSettings() {
        openModels();
        onView(row("redact")).perform(scrollTo(), click());
        onView(withText(R.string.setting_redact_mode)).check(matches(isDisplayed()));
        onView(withText(R.string.setting_redact_email)).check(matches(isDisplayed()));
        pressBack();
        onView(withId(R.id.models_heading)).check(matches(isDisplayed()));
    }

    @Test
    public void tryItRunsTheRealModelOnWhatTheUserTyped() throws Exception {
        app.close();
        TestModels.ensureInstalled("redact");
        app = ActivityScenario.launch(MainActivity.class);
        openModels();
        onView(row("redact")).perform(scrollTo(), click());
        onView(withId(androidx.preference.R.id.recycler_view)).perform(swipeUp());

        onView(withId(R.id.try_input)).perform(replaceText("Email me at alex@example.com"));
        onView(withId(R.id.try_run)).perform(click());

        // The answer comes from the model on its own thread; wait for it to be shown.
        String[] shown = new String[1];
        assertTrue("no result appeared: " + shown[0], TestModels.await(() -> {
            app.onActivity(a -> shown[0] = ((android.widget.TextView)
                    a.findViewById(R.id.try_result)).getText().toString());
            return shown[0].contains("[EMAIL_1]");
        }, 20_000));
        assertTrue(shown[0], shown[0].contains("alex@example.com"));
    }

    @Test
    public void thePrivacyScreenDisclosesTheLicenseCheck() {
        onView(withId(R.id.tab_settings)).perform(click());
        // Privacy sits near the end of the list; rows off screen do not exist until scrolled to.
        onView(withId(androidx.preference.R.id.recycler_view)).perform(swipeUp());
        onView(withText(R.string.settings_privacy)).perform(click());
        onView(withText(containsString("License check"))).perform(scrollTo())
                .check(matches(isDisplayed()));
        onView(withText(containsString("cannot be turned off"))).check(matches(isDisplayed()));
    }
}

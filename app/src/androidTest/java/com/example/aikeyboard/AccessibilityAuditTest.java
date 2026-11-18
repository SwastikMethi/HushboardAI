// Accessibility audit. The companion app's screens are run through Google's accessibility
// checks (missing labels, touch targets under 48dp, low contrast, clipped text). The keyboard
// lives in its own window, out of those checks' reach, so its controls are checked by hand
// here: every one has a spoken label, and the tools and panel actions are large enough to
// hit. Colour pairs are checked against the WCAG contrast ratios, and the one animation is
// checked to stay off when the system's animations are off.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.accessibility.AccessibilityChecks;
import androidx.test.espresso.action.ViewActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.ime.view.AiToggleView;
import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.ui.chat.ChatActivity;
import com.example.aikeyboard.ui.chat.ChatChecks;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class AccessibilityAuditTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;

    private final Context context = getInstrumentation().getTargetContext();
    private final SettingsRepository settings = TestModels.container().settings;
    private final UiDevice device = UiDevice.getInstance(getInstrumentation());

    @BeforeClass
    public static void checkEveryScreenFromItsRoot() {
        // From here on, every Espresso check in this process also audits the whole screen.
        AccessibilityChecks.enable().setRunChecksFromRootView(true);
    }

    @AfterClass
    public static void stopChecking() {
        // Other test classes drive screens mid-change and through system dialogs; the audit
        // belongs to this class only.
        ViewActions.clearGlobalAssertions();
    }

    @After
    public void tearDown() {
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void auditScreen(ActivityScenario<MainActivity> app, Runnable open, int landmark) {
        if (open != null) {
            app.onActivity(activity -> open.run());
            getInstrumentation().waitForIdleSync();
        }
        onView(withId(landmark)).check(matches(isDisplayed()));
        onView(isRoot()).perform(audit());
    }

    // The checks run whenever an action is performed, so this one does nothing itself. It
    // used to be "close the keyboard", which waits on the system and once timed out with a
    // keyboard still settling after a reinstall.
    private static ViewAction audit() {
        return ViewActions.actionWithAssertions(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isRoot();
            }

            @Override
            public String getDescription() {
                return "audit the screen";
            }

            @Override
            public void perform(UiController uiController, View view) {
                uiController.loopMainThreadUntilIdle();
            }
        });
    }

    // ---- Companion app ----

    @Test
    public void theAuditItselfRejectsAnUnlabelledUndersizedButton() {
        // Proof that the checks really run: a control that breaks the rules must fail them.
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(activity -> {
                android.widget.ImageButton bad = new android.widget.ImageButton(activity);
                ((android.view.ViewGroup) activity.findViewById(R.id.root)).addView(bad, 0,
                        new android.view.ViewGroup.LayoutParams(dp(20), dp(20)));
            });
            getInstrumentation().waitForIdleSync();
            try {
                onView(isRoot()).perform(audit());
            } catch (RuntimeException rejected) {
                assertTrue(String.valueOf(rejected.getMessage()),
                        rejected.getClass().getSimpleName().contains("Accessibility"));
                return;
            }
            throw new AssertionError("the audit let an unlabelled 20dp button through");
        }
    }

    @Test
    public void theThreeTabsPassTheAccessibilityChecks() {
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            auditScreen(app, null, R.id.setup_status);
            onView(withId(R.id.tab_models)).perform(click());
            auditScreen(app, null, R.id.models_heading);
            onView(withId(R.id.tab_settings)).perform(click());
            auditScreen(app, null, R.id.tabs);
        }
    }

    @Test
    public void theScreensOpenedFromTheTabsPassTheAccessibilityChecks() {
        MainActivity[] holder = new MainActivity[1];
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            app.onActivity(activity -> holder[0] = activity);
            auditScreen(app, () -> holder[0].openModelDetail("redact"), R.id.tabs);
            auditScreen(app, holder[0]::openPrivacy, R.id.tabs);
            auditScreen(app, holder[0]::openAppPreferences, R.id.tabs);
            auditScreen(app, () -> holder[0].openToolsFor(ModelDescriptor.Group.VOICE),
                    R.id.voice_steps);
            auditScreen(app, () -> holder[0].openToolsFor(ModelDescriptor.Group.IMAGES),
                    R.id.image_steps);
            auditScreen(app, () -> holder[0].openToolsFor(ModelDescriptor.Group.DRAWING),
                    R.id.drawing_status);
        }
    }

    // ---- Kids Chat ----

    @Test
    public void kidsChatPassesTheAccessibilityChecksInEachOfItsStates() throws Exception {
        for (String id : new String[] {"toxic", "redact", "moderator", "emo"}) {
            TestModels.ensureInstalled(id);
        }
        try (ActivityScenario<ChatActivity> chat = ActivityScenario.launch(ChatActivity.class)) {
            getInstrumentation().waitForIdleSync();
            onView(withId(R.id.chat_empty)).check(matches(isDisplayed()));
            onView(isRoot()).perform(audit());

            // A message in the conversation, and a refused one with its warning.
            sayInChat("See you after school");
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_bubble), 60_000));
            sayInChat("I will find you and hurt you, you worthless idiot");
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_notice), 60_000));
            onView(isRoot()).perform(audit());

            // The personal-details card with its three buttons.
            sayInChat("My email is mia@example.com");
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_personal), 60_000));
            onView(isRoot()).perform(audit());

            // Emoji ideas above the field, and the drawing pad with its crayons.
            onView(withId(R.id.chat_change)).perform(click());
            onView(withId(R.id.chat_input)).perform(
                    ViewActions.replaceText("I love playing football with my friends"));
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_ideas), 60_000));
            onView(isRoot()).perform(audit());
            onView(withId(R.id.chat_draw)).perform(click());
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_pad), 60_000));
            onView(isRoot()).perform(audit());

            // The card for a photo that shows a person. The picture model is stood in for,
            // and told to see one, because the project holds no photo of a person.
            onView(withId(R.id.chat_pad_close)).perform(click());
            AppContainer app = TestModels.container();
            ImageFindings aPerson = new ImageFindings("photo", List.of(), 0.5, 1, 0, 0);
            File house = new File(context.getCacheDir(), "audit-house.png");
            try (FileOutputStream out = new FileOutputStream(house)) {
                VisionModelsTest.harmlessPicture().compress(Bitmap.CompressFormat.PNG, 90, out);
            }
            chat.onActivity(activity -> {
                new ViewModelProvider(activity).get(ChatActivity.Session.class).checks =
                        new ChatChecks(app.toxic, app.redact, app.moderator, app.tongue,
                                new KidsChatTest.Stub<>("eye", aPerson), Set.of(),
                                ChatChecks.namedThings(context), ChatChecks.kidRules());
                activity.onPicturePicked(Uri.fromFile(house));
            });
            assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_people), 60_000));
            onView(isRoot()).perform(audit());

            // A spoken message: the listening panel, and the card with the words that were
            // heard. Known speech stands where the microphone would be.
            onView(withId(R.id.chat_drop_photo)).perform(ViewActions.scrollTo(), click());
            AudioClip speech = TestSpeech.english();
            if (speech != null) {
                TestModels.ensureInstalled("voz");
                TestModels.allowMicrophone();
                AudioCaptureController real = app.capture;
                app.capture = new FakeCapture(speech);
                try {
                    // The button is a microphone only while nothing is typed.
                    onView(withId(R.id.chat_input)).perform(ViewActions.replaceText(""));
                    onView(withId(R.id.chat_send)).perform(click());
                    assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_listen), 60_000));
                    onView(isRoot()).perform(audit());
                    onView(withId(R.id.chat_listen_done)).perform(click());
                    assertTrue(TestModels.await(() -> isShowing(chat, R.id.chat_heard),
                            3 * 60_000));
                    onView(isRoot()).perform(audit());
                } finally {
                    app.capture = real;
                }
            }
        }
    }

    private static void sayInChat(String text) {
        onView(withId(R.id.chat_input)).perform(ViewActions.replaceText(text));
        onView(withId(R.id.chat_send)).perform(click());
    }

    private static boolean isShowing(ActivityScenario<ChatActivity> chat, int viewId) {
        boolean[] showing = {false};
        chat.onActivity(activity -> {
            View view = activity.findViewById(viewId);
            showing[0] = view != null && view.isShown();
        });
        return showing[0];
    }

    // ---- Keyboard ----

    private void showKeyboard() throws Exception {
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // Everything tappable in the keyboard's window, with what a screen reader would say.
    private List<String> unlabelledControls() {
        List<String> unlabelled = new ArrayList<>();
        for (UiObject2 node : device.findObjects(By.pkg(PKG).clickable(true))) {
            String id = String.valueOf(node.getResourceName());
            if (id.startsWith(PKG + ":id/field_")) continue; // the test host's own fields
            String spoken = node.getContentDescription() != null
                    ? node.getContentDescription() : node.getText();
            if (spoken == null || spoken.trim().isEmpty()) {
                unlabelled.add(id + " at " + node.getVisibleBounds());
            }
        }
        return unlabelled;
    }

    @Test
    public void everyKeyboardControlHasASpokenLabelAndToolsAreBigEnoughToHit() throws Exception {
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        try (ActivityScenario<EditorTestActivity> host =
                ActivityScenario.launch(EditorTestActivity.class)) {
            showKeyboard();
            assertTrue("no label: " + unlabelledControls(), unlabelledControls().isEmpty());
            // Keys are announced one by one, each with a name.
            for (String key : new String[] {"q", "Shift", "Backspace", "Space", "Emoji"}) {
                assertNotNull("no key announced as " + key,
                        device.findObject(By.pkg(PKG).desc(key)));
            }
            for (String id : new String[] {"ai_toggle", "tool_settings"}) {
                Rect bounds = device.findObject(By.res(PKG, id)).getVisibleBounds();
                assertTrue(id + " is " + bounds.width() + "x" + bounds.height() + " px",
                        bounds.width() >= dp(48) && bounds.height() >= dp(32));
            }
            Rect settingsButton = device.findObject(By.res(PKG, "tool_settings"))
                    .getVisibleBounds();
            assertTrue("tool buttons should be a full 48dp target",
                    settingsButton.height() >= dp(48) - 1);

            device.findObject(By.res(PKG, "ai_toggle")).click();
            device.wait(Until.findObject(By.res(PKG, "ai_panel_master")), TIMEOUT_MS);
            assertTrue("no label in the AI panel: " + unlabelledControls(),
                    unlabelledControls().isEmpty());
            for (String id : new String[] {"ai_panel_back", "ai_panel_close",
                    "ai_panel_manage"}) {
                Rect bounds = device.findObject(By.res(PKG, id)).getVisibleBounds();
                assertTrue(id + " is too small: " + bounds,
                        bounds.width() >= dp(48) - 1 && bounds.height() >= dp(48) - 1);
            }
        }
    }

    // ---- Colour ----

    private static double luminance(int color) {
        double[] channel = {Color.red(color) / 255.0, Color.green(color) / 255.0,
                Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) {
            channel[i] = channel[i] <= 0.03928 ? channel[i] / 12.92
                    : Math.pow((channel[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    private double contrast(int foreground, int background) {
        double a = luminance(context.getColor(foreground));
        double b = luminance(context.getColor(background));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    @Test
    public void textAndControlsMeetTheContrastRatios() {
        // Text on the surface it sits on: at least 4.5 to 1.
        int[][] text = {
                {R.color.text_primary, R.color.canvas},
                {R.color.text_secondary, R.color.canvas},
                {R.color.text_primary, R.color.surface},
                {R.color.text_secondary, R.color.surface},
                {R.color.accent_blue, R.color.canvas},
                {R.color.accent_blue, R.color.surface},
                {R.color.on_accent_blue, R.color.accent_blue},
                {R.color.key_label, R.color.key_letter},
                {R.color.key_label_secondary, R.color.key_function},
                {R.color.key_label_secondary, R.color.keyboard_background},
                {R.color.text_primary, R.color.ai_panel_top},
                {R.color.key_label_secondary, R.color.ai_panel_top},
                {R.color.accent_blue, R.color.ai_panel_bottom},
        };
        for (int[] pair : text) {
            double ratio = contrast(pair[0], pair[1]);
            assertTrue(context.getResources().getResourceEntryName(pair[0]) + " on "
                    + context.getResources().getResourceEntryName(pair[1]) + " is "
                    + ratio, ratio >= 4.5);
        }
        // Shapes that carry meaning (a key against the keyboard, a switch against its
        // card, the switch thumb against its track): at least 3 to 1.
        int[][] shapes = {
                {R.color.switch_on, R.color.surface},
                {R.color.text_primary, R.color.switch_on},
                {R.color.key_label_secondary, R.color.key_letter},
                {R.color.accent_blue, R.color.keyboard_background},
        };
        for (int[] pair : shapes) {
            double ratio = contrast(pair[0], pair[1]);
            assertTrue(context.getResources().getResourceEntryName(pair[0]) + " on "
                    + context.getResources().getResourceEntryName(pair[1]) + " is "
                    + ratio, ratio >= 3.0);
        }
    }

    // ---- Motion ----

    @Test
    public void theEnableSweepPlaysOnceOnRequestAndNeverWithAnimationsOff() throws Exception {
        String before = device.executeShellCommand(
                "settings get global animator_duration_scale").trim();
        try {
            AiToggleView[] view = new AiToggleView[1];
            getInstrumentation().runOnMainSync(() -> view[0] = new AiToggleView(context, null));

            device.executeShellCommand("settings put global animator_duration_scale 1");
            assertTrue(TestModels.await(android.animation.ValueAnimator::areAnimatorsEnabled, 3000));
            getInstrumentation().runOnMainSync(() -> {
                view[0].setOn(true);
                assertFalse("showing the saved state must never animate",
                        view[0].isSweeping());
                view[0].playEnableSweep();
                assertTrue("asked for, the sweep should start", view[0].isSweeping());
            });
            assertTrue("the sweep should end by itself", TestModels.await(
                    () -> !view[0].isSweeping(), AiToggleView.ENABLE_SWEEP_MS + 2000));

            device.executeShellCommand("settings put global animator_duration_scale 0");
            assertTrue(TestModels.await(
                    () -> !android.animation.ValueAnimator.areAnimatorsEnabled(), 3000));
            getInstrumentation().runOnMainSync(() -> {
                view[0].playEnableSweep();
                assertFalse("with animations off there is no sweep", view[0].isSweeping());
            });
        } finally {
            device.executeShellCommand("settings put global animator_duration_scale "
                    + ("null".equals(before) || before.isEmpty() ? "1" : before));
        }
    }
}

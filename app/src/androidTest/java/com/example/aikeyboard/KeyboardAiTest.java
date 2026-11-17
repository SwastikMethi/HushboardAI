// AI in the keyboard, end to end with the real Emo model: a suggestion appears for the draft,
// a tap inserts it, and nothing runs where it must not (AI off, password field, blocked app,
// model not installed).
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class KeyboardAiTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    // A suggestion slot holding something that is not a word: an emoji.
    private static final BySelector EMOJI_SLOT =
            By.res(PKG, "suggestion_1").text(Pattern.compile("[^\\p{L}\\p{N}\\s]+"));

    private final SettingsRepository settings = TestModels.container().settings;
    private UiDevice device;
    private ActivityScenario<EditorTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("emo");
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of());
        settings.setModelEnabled("emo", true);
        // Each test turns on the other models it needs; none may leak in from a previous one.
        for (String other : new String[] {"redact", "toxic", "tongue", "gist"}) {
            settings.setModelEnabled(other, false);
        }
        setAi(true);
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @After
    public void tearDown() {
        scenario.close();
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of());
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void setAi(boolean on) throws InterruptedException {
        settings.setBoolean(AppSettings.AI_ENABLED, on);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled() == on, 5000));
    }

    private void focus(String fieldId) {
        device.wait(Until.findObject(By.res(PKG, fieldId)), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
    }

    private void tap(String... labels) {
        for (String label : labels) {
            UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
            assertNotNull("key not found: " + label, key);
            key.click();
        }
        device.waitForIdle();
    }

    private String text(int fieldId) {
        String[] result = new String[1];
        scenario.onActivity(a ->
                result[0] = ((EditText) a.findViewById(fieldId)).getText().toString());
        return result[0];
    }

    private void typePizza() {
        tap("p", "i", "z", "z", "a", "Space");
    }

    @Test
    public void anEmojiIsSuggestedForTheDraftAndATapInsertsIt() {
        focus("field_plain");
        typePizza();

        UiObject2 slot = device.wait(Until.findObject(EMOJI_SLOT), TIMEOUT_MS);
        assertNotNull("no emoji suggestion appeared", slot);
        String emoji = slot.getText();
        slot.click();
        device.waitForIdle();

        assertEquals("pizza " + emoji, text(R.id.field_plain));
    }

    @Test
    public void nothingIsSuggestedWhileAiIsOff() throws Exception {
        setAi(false);
        focus("field_plain");
        typePizza();
        assertNull("AI is off: no model may run", device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAPasswordField() {
        focus("field_password");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAFieldThatAsksNotToBeLearnedFrom() {
        focus("field_no_learning");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void nothingIsSuggestedInAnAppTheUserBlocked() throws Exception {
        settings.setStringSet(AppSettings.BLOCKED_APPS, Set.of(PKG));
        assertTrue(TestModels.await(
                () -> settings.latest().stringSet(AppSettings.BLOCKED_APPS).contains(PKG), 5000));
        focus("field_plain");
        typePizza();
        assertNull(device.wait(Until.findObject(EMOJI_SLOT), 2500));
    }

    @Test
    public void theAiPanelSwitchIsTheSameSettingTheCompanionAppUses() throws Exception {
        setAi(false);
        focus("field_plain");
        UiObject2 pill = device.wait(Until.findObject(By.res(PKG, "ai_toggle")), TIMEOUT_MS);
        assertNotNull("no AI control in the tool row", pill);
        assertTrue(pill.getContentDescription().contains("off"));

        pill.click();
        UiObject2 master = device.wait(
                Until.findObject(By.res(PKG, "ai_panel_master")), TIMEOUT_MS);
        assertNotNull("the AI panel did not open", master);
        master.click();

        assertTrue("the panel switch must write the shared setting",
                TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        device.findObject(By.res(PKG, "ai_panel_close")).click();
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "ai_toggle").descContains("on")),
                TIMEOUT_MS));
        assertFalse(device.hasObject(By.res(PKG, "ai_panel_master")));
    }

    @Test
    public void aModelSwitchedOnButNotInstalledOffersSetupInsteadOfRunning() throws Exception {
        TestModels.container().models.delete("emo");
        assertTrue(TestModels.await(
                () -> TestModels.container().models.bytesOnDisk("emo") == 0, 10_000));
        settings.setModelEnabled("emo", true);
        focus("field_plain");
        typePizza();

        assertNotNull("expected a setup hint", device.wait(Until.findObject(
                By.res(PKG, "suggestion_1").text("Set up AI models")), TIMEOUT_MS));
        assertEquals("typing must be unaffected", "pizza ", text(R.id.field_plain));
    }

    // ---- Redact and Toxic ----

    private static final String WITH_EMAIL = "mail me at anna@example.hu ";

    private void given(int fieldId, String text) {
        scenario.onActivity(a -> {
            EditText field = a.findViewById(fieldId);
            field.setText(text);
            field.setSelection(text.length());
        });
        device.waitForIdle();
    }

    private void useRedact(String mode) throws Exception {
        TestModels.ensureInstalled("redact");
        // ensureInstalled may have put the companion app on screen in place of the test host.
        scenario.close();
        scenario = ActivityScenario.launch(EditorTestActivity.class);
        settings.setModelEnabled("redact", true);
        String key = AppSettings.modelSettingKey("redact", ModelRegistry.REDACT_MODE);
        settings.setString(key, mode);
        assertTrue(TestModels.await(() -> mode.equals(settings.latest().string(key, ""))
                && settings.latest().modelEnabled("redact"), 5000));
    }

    private UiObject2 slot(String id, String text) {
        return device.wait(Until.findObject(By.res(PKG, id).text(text)), TIMEOUT_MS);
    }

    @Test
    public void personalInformationCanBeReviewedMaskedAndPutBack() throws Exception {
        useRedact(ModelRegistry.REDACT_MODE_MASK);
        focus("field_plain");
        given(R.id.field_plain, WITH_EMAIL);

        assertNotNull("no privacy notice", slot("suggestion_1", "Personal information detected"));
        assertEquals("a notice alone changes nothing", WITH_EMAIL, text(R.id.field_plain));
        slot("suggestion_2", "Review").click();

        UiObject2 detail = device.wait(Until.findObject(By.res(PKG, "notice_detail")), TIMEOUT_MS);
        assertNotNull("the review panel did not open", detail);
        assertTrue(detail.getText(), detail.getText().contains("anna@example.hu"));
        assertTrue(detail.getText(), detail.getText().contains("[EMAIL_1]"));
        assertEquals("opening the review changes nothing", WITH_EMAIL, text(R.id.field_plain));

        device.findObject(By.res(PKG, "notice_primary").text("Mask in message")).click();
        assertTrue("masking was not applied: " + text(R.id.field_plain), TestModels.await(
                () -> "mail me at [EMAIL_1] ".equals(text(R.id.field_plain)), 5000));

        slot("suggestion_2", "Undo").click();
        assertTrue("undo did not restore the text: " + text(R.id.field_plain),
                TestModels.await(() -> WITH_EMAIL.equals(text(R.id.field_plain)), 5000));
    }

    @Test
    public void warnOnlyShowsWhatWasFoundButOffersNoChange() throws Exception {
        useRedact(ModelRegistry.REDACT_MODE_WARN);
        focus("field_plain");
        given(R.id.field_plain, WITH_EMAIL);

        slot("suggestion_2", "Review").click();
        assertNotNull(device.wait(Until.findObject(
                By.res(PKG, "notice_detail").textContains("anna@example.hu")), TIMEOUT_MS));
        assertNull("warn-only must not offer masking",
                device.findObject(By.res(PKG, "notice_primary")));
        assertEquals(WITH_EMAIL, text(R.id.field_plain));
    }

    @Test
    public void aDismissedNoticeStaysAwayUntilTheTextChanges() throws Exception {
        useRedact(ModelRegistry.REDACT_MODE_WARN);
        focus("field_plain");
        given(R.id.field_plain, WITH_EMAIL);
        assertNotNull(slot("suggestion_1", "Personal information detected"));

        slot("suggestion_3", "Dismiss").click();
        assertTrue("the notice did not go away", device.wait(Until.gone(
                By.res(PKG, "suggestion_1").text("Personal information detected")), TIMEOUT_MS));
        // The same text is analysed again after a moment; the notice must not come back.
        assertNull(device.wait(Until.findObject(
                By.res(PKG, "suggestion_1").text("Personal information detected")), 2000));

        tap("o", "k", "Space");
        assertNotNull("the text changed, so the notice applies again",
                slot("suggestion_1", "Personal information detected"));
    }

    @Test
    public void aThreatIsFlaggedForReviewAndSendingIsNeverBlocked() throws Exception {
        TestModels.ensureInstalled("toxic");
        scenario.close();
        scenario = ActivityScenario.launch(EditorTestActivity.class);
        settings.setModelEnabled("toxic", true);
        settings.setModelEnabled("redact", false);
        assertTrue(TestModels.await(() -> settings.latest().modelEnabled("toxic")
                && !settings.latest().modelEnabled("redact"), 5000));
        String threat = "I will find you and hurt you, you worthless idiot ";
        EditorTestActivity.lastEditorAction = -1;
        focus("field_send");
        given(R.id.field_send, threat);

        assertNotNull("no tone notice", slot("suggestion_1", "Wording flagged for review"));
        slot("suggestion_2", "Review").click();
        UiObject2 detail = device.wait(Until.findObject(By.res(PKG, "notice_detail")), TIMEOUT_MS);
        assertNotNull(detail);
        assertTrue(detail.getText(), detail.getText().contains("Threat")
                || detail.getText().contains("Abusive"));
        assertNull("a tone notice offers no edit",
                device.findObject(By.res(PKG, "notice_primary")));
        device.findObject(By.res(PKG, "notice_back")).click();

        // The notice is information only: Send still does exactly what the user pressed.
        tap("Send");
        assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
                EditorTestActivity.lastEditorAction);
        assertEquals(threat, text(R.id.field_send));
    }

    // ---- Tongue and Gist ----

    private static final String JAPANESE_WITH_EMAIL =
            "メールアドレスは anna@example.hu です。明日の会議についてご連絡ください。よろしくお願いします。 ";

    private void use(String modelId, boolean on) throws Exception {
        if (on) TestModels.ensureInstalled(modelId);
        settings.setModelEnabled(modelId, on);
        assertTrue(TestModels.await(() -> settings.latest().modelEnabled(modelId) == on, 5000));
    }

    private void relaunchHost() {
        // Installing a model may have put the companion app on screen in place of the host.
        scenario.close();
        scenario = ActivityScenario.launch(EditorTestActivity.class);
    }

    @Test
    public void aLanguageThePrivacyCheckDoesNotCoverIsSkippedAndThePanelSaysSo() throws Exception {
        useRedact(ModelRegistry.REDACT_MODE_WARN);
        use("tongue", true);
        focus("field_plain");
        given(R.id.field_plain, JAPANESE_WITH_EMAIL);

        assertNull("Redact must not be run on a language it does not cover", device.wait(
                Until.findObject(By.res(PKG, "suggestion_1").text("Personal information detected")),
                3000));
        device.findObject(By.res(PKG, "ai_toggle")).click();
        assertNotNull("the panel should name the detected language", device.wait(
                Until.findObject(By.res(PKG, "ai_model_status").text("Looks like Japanese")),
                TIMEOUT_MS));
        assertNotNull("the panel should say the privacy check was skipped", device.findObject(
                By.res(PKG, "ai_model_status").text("Does not cover Japanese")));
    }

    @Test
    public void withTheLanguageHintOffTheSameTextIsStillChecked() throws Exception {
        useRedact(ModelRegistry.REDACT_MODE_WARN);
        use("tongue", false);
        focus("field_plain");
        given(R.id.field_plain, JAPANESE_WITH_EMAIL);

        assertNotNull("without the hint, the privacy check runs as usual",
                slot("suggestion_1", "Personal information detected"));
    }

    @Test
    public void topicsAreComputedOnlyOnRequestAndInsertedOnlyOnTap() throws Exception {
        use("gist", true);
        use("redact", false);
        relaunchHost();
        String draft = "We had pizza and pasta for dinner at the new Italian restaurant ";
        focus("field_plain");
        given(R.id.field_plain, draft);

        device.findObject(By.res(PKG, "ai_toggle")).click();
        UiObject2 tag = device.wait(Until.findObject(By.res(PKG, "ai_panel_tag")), TIMEOUT_MS);
        assertNotNull("no Tag action in the AI panel", tag);
        tag.click();

        assertNotNull("the topics panel did not open", device.wait(Until.findObject(
                By.res(PKG, "notice_title").text("Topics in this draft")), TIMEOUT_MS));
        String topics = device.findObject(By.res(PKG, "notice_detail")).getText();
        assertFalse("expected at least one topic", topics.equals("No topic stood out."));
        assertEquals("showing topics changes nothing", draft, text(R.id.field_plain));

        device.findObject(By.res(PKG, "notice_primary").text("Insert as hashtags")).click();
        assertTrue("hashtags were not inserted: " + text(R.id.field_plain), TestModels.await(
                () -> text(R.id.field_plain).startsWith(draft + " #"), 5000));
    }

    @Test
    public void aModelThatCrashedTheKeyboardIsSwitchedOffAtTheNextStart() throws Exception {
        AppContainer app = TestModels.container();
        app.breaker.loading("emo"); // as if the process had died inside the load

        app.disableModelsThatCrashed();

        assertTrue(TestModels.await(() -> !settings.latest().modelEnabled("emo"), 5000));
        assertTrue(settings.latest().bool(AppSettings.modelCrashedKey("emo"), false));
        // Turning it back on is the user's decision, and clears the warning.
        settings.setModelEnabled("emo", true);
        assertTrue(TestModels.await(
                () -> !settings.latest().bool(AppSettings.modelCrashedKey("emo"), true), 5000));
    }
}

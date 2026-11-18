// The kids' chat on a device, with the real models: what its checks refuse, what they offer
// masked and what they let through, first on their own and then through the screen. No
// explicit imagery is used: the refused picture is a harmless drawing checked at a threshold
// of zero.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.ui.chat.ChatActivity;
import com.example.aikeyboard.ui.chat.ChatChecks;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class KidsChatTest {

    private static final String TAG = "KidsChat";
    private static final long RUN_MS = 60_000;
    private static final String THREAT = "I will find you and hurt you, you worthless idiot";
    private static final String WITH_EMAIL = "My email is mia@example.com";
    private static final String MASKED_EMAIL = "My email is [EMAIL_1]";

    // What the tone model is asked about when measuring, strongest first. None holds a slur.
    private static final String[] SAMPLES = {
            "You are a stupid idiot and I hate you",
            "Nobody likes you, loser",
            "I will beat you up after school",
            "I know where you live and I will hurt you",
            "People like you should not exist",
            "This game is so damn hard",
            "That was a shitty game",
            "What the fuck was that",
            "Do you want to play football after school?",
            "You killed it in the match today, great job!",
    };

    private final Context context = ApplicationProvider.getApplicationContext();
    private final AppContainer app = TestModels.container();
    private ChatChecks checks;
    private ActivityScenario<ChatActivity> chat;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("toxic");
        TestModels.ensureInstalled("redact");
        TestModels.ensureInstalled("moderator");
        checks = ChatChecks.with(app, context, ChatChecks.kidRules());
    }

    @After
    public void tearDown() {
        if (chat != null) chat.close();
    }

    @Test
    public void aThreatIsRefused() throws Exception {
        assertEquals(Outcome.UNKIND, check(THREAT).outcome);
    }

    @Test
    public void aSwearWordAimedAtNobodyIsRefusedByTheAppsOwnList() throws Exception {
        assertEquals(Outcome.UNKIND, check("That was a shitty game").outcome);
    }

    @Test
    public void aFriendlyMessageIsClear() throws Exception {
        assertEquals(Outcome.CLEAR, check("Do you want to play football after school?").outcome);
    }

    @Test
    public void anEmailAddressIsOfferedMasked() throws Exception {
        TextVerdict verdict = check(WITH_EMAIL);

        assertEquals(Outcome.PERSONAL, verdict.outcome);
        assertEquals(MASKED_EMAIL, verdict.masked);
    }

    @Test
    public void aHarmlessPictureMayBeSentAndAFlaggedOneMayNot() throws Exception {
        Bitmap picture = VisionModelsTest.harmlessPicture();

        assertTrue("a drawing of a house was refused",
                onInferenceThread(() -> checks.pictureAllowed(picture)));

        // Nothing explicit is shown to the model: at a threshold of zero any picture counts.
        ChatChecks atZero = ChatChecks.with(app, context, new AppSettings(1, Map.of(
                AppSettings.modelSettingKey("moderator", ModelRegistry.MODERATOR_THRESHOLD), 0)));
        assertFalse("a flagged picture was let through",
                onInferenceThread(() -> atZero.pictureAllowed(picture)));
    }

    // ---- The screen ----

    @Test
    public void aThreatStaysInTheFieldWithAWarningAndIsNotSent() throws Exception {
        openChat();

        say(THREAT);

        assertTrue("no warning; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(context.getString(R.string.chat_unkind)),
                RUN_MS));
        assertEquals(0, sent());
        assertEquals("the message must stay for editing", THREAT, shown(R.id.chat_input));
    }

    @Test
    public void personalDetailsRaiseACardAndSendLikeThisSendsThemMasked() throws Exception {
        openChat();
        sayWithPersonalDetails();
        assertEquals(WITH_EMAIL, shown(R.id.chat_personal_typed));
        assertEquals("nothing is sent before a choice is made", 0, sent());

        onView(withId(R.id.chat_send_masked)).perform(click());

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals(MASKED_EMAIL, bubble(0));
        assertEquals("the field is emptied once the message has gone", "",
                shown(R.id.chat_input));
        onView(withId(R.id.chat_personal)).check(matches(not(isDisplayed())));
    }

    @Test
    public void changeItSendsNothingAndGivesTheMessageBackForEditing() throws Exception {
        openChat();
        sayWithPersonalDetails();

        onView(withId(R.id.chat_change)).perform(click());

        onView(withId(R.id.chat_personal)).check(matches(not(isDisplayed())));
        assertEquals(0, sent());
        assertEquals(WITH_EMAIL, shown(R.id.chat_input));
        onView(withId(R.id.chat_input)).check(matches(isEnabled()));
    }

    @Test
    public void sendAsWrittenSendsTheMessageAsItWasTyped() throws Exception {
        openChat();
        sayWithPersonalDetails();

        onView(withId(R.id.chat_send_written)).perform(click());

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals(WITH_EMAIL, bubble(0));
    }

    @Test
    public void aCleanMessageIsSentAndTheOtherUserSeesItAndCanReply() throws Exception {
        openChat();

        say("Do you want to play football after school?");

        assertTrue("not sent; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals("Do you want to play football after school?", bubble(0));
        assertTrue("your own message sits on the right", onTheRight(0));

        onView(withId(R.id.chat_user_two)).perform(click());
        getInstrumentation().waitForIdleSync();
        assertFalse("for the other user the same message sits on the left", onTheRight(0));

        say("Yes! See you at four");
        assertTrue(TestModels.await(() -> sent() == 2, RUN_MS));
        assertEquals("Yes! See you at four", bubble(1));
        assertTrue(onTheRight(1));
    }

    @Test
    public void turningThePhoneKeepsTheConversation() throws Exception {
        openChat();
        say("See you tomorrow");
        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));

        chat.recreate();
        getInstrumentation().waitForIdleSync();

        assertEquals(1, sent());
        assertEquals("See you tomorrow", bubble(0));
    }

    @Test
    public void theChatIsLockedUntilEverySafetyModelIsInstalled() throws Exception {
        assumeTrue("needs network to put the model back", TestModels.online());
        app.models.delete("moderator");
        try {
            assertTrue(TestModels.awaitStatus("moderator",
                    status -> status.state == AssetState.NOT_INSTALLED, 30_000));
            openChat();
            onView(withId(R.id.chat_locked)).check(matches(isDisplayed()));
            onView(withId(R.id.chat_send)).check(matches(not(isEnabled())));
            onView(withId(R.id.chat_input)).check(matches(not(isEnabled())));

            onView(withId(R.id.chat_download)).perform(click());

            assertTrue("the download did not finish", TestModels.awaitStatus("moderator",
                    status -> status.state == AssetState.READY, 5 * 60_000));
            getInstrumentation().waitForIdleSync();
            onView(withId(R.id.chat_locked)).check(matches(not(isDisplayed())));
            onView(withId(R.id.chat_send)).check(matches(isEnabled()));
        } finally {
            TestModels.ensureInstalled("moderator");
        }
    }

    private void openChat() {
        chat = ActivityScenario.launch(ChatActivity.class);
        getInstrumentation().waitForIdleSync();
    }

    private void say(String text) {
        onView(withId(R.id.chat_input)).perform(replaceText(text));
        onView(withId(R.id.chat_send)).perform(click());
    }

    private void sayWithPersonalDetails() throws InterruptedException {
        say(WITH_EMAIL);
        assertTrue("no card; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_personal_masked).equals(MASKED_EMAIL), RUN_MS));
    }

    /** The text of a view on the chat screen, or "" while it is not showing. */
    private String shown(int viewId) {
        String[] text = {""};
        chat.onActivity(activity -> {
            TextView view = activity.findViewById(viewId);
            if (view != null && view.isShown()) text[0] = view.getText().toString();
        });
        return text[0];
    }

    /** How many messages are in the conversation. */
    private int sent() {
        int[] count = {0};
        chat.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.chat_messages);
            if (list.getAdapter() != null) count[0] = list.getAdapter().getItemCount();
        });
        return count[0];
    }

    private String bubble(int position) {
        String[] text = {""};
        chat.onActivity(activity -> text[0] = ((TextView) row(activity, position)
                .findViewById(R.id.chat_bubble_text)).getText().toString());
        return text[0];
    }

    private boolean onTheRight(int position) {
        boolean[] right = {false};
        chat.onActivity(activity -> {
            View row = row(activity, position);
            View bubble = row.findViewById(R.id.chat_bubble);
            right[0] = bubble.getLeft() + bubble.getWidth() / 2 > row.getWidth() / 2;
        });
        return right[0];
    }

    private static View row(ChatActivity activity, int position) {
        RecyclerView list = activity.findViewById(R.id.chat_messages);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder == null) throw new AssertionError("no message at " + position);
        return holder.itemView;
    }

    // Measurement, not a check: what the tone model alone makes of each sample under the
    // chat's rules, and whether the word list catches it. Run with -e chatprobe true.
    @Test
    public void whatTheToneModelMakesOfTheSamples() throws Exception {
        assumeTrue("measurement only: pass -e chatprobe true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatprobe")));
        AppSettings rules = ChatChecks.kidRules();
        for (int i = 0; i < SAMPLES.length; i++) {
            String sample = SAMPLES[i];
            ToxicScores tone = onInferenceThread(() -> app.toxic.run(sample, rules));
            List<String> flagged = ToxicPolicy.flagged(tone.scores, tone.thresholds, rules);
            String line = "model=" + flagged + " chat=" + check(sample).outcome
                    + String.format(" hateful=%.2f abusive=%.2f threat=%.2f",
                            tone.scores.get("hateful"), tone.scores.get("abusive"),
                            tone.scores.get("threat"));
            Log.i(TAG, "sample " + (i + 1) + ": " + line);
            Bundle status = new Bundle();
            status.putString("chat.sample" + (i + 1), line);
            getInstrumentation().sendStatus(0, status);
        }
    }

    private TextVerdict check(String text) throws Exception {
        return onInferenceThread(() -> checks.text(text));
    }

    private <T> T onInferenceThread(Callable<T> work) throws Exception {
        return app.inference.submit(work).get(5, TimeUnit.MINUTES);
    }
}

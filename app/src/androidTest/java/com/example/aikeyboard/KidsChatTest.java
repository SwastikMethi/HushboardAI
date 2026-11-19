// The kids' chat on a device, with the real models: what its checks refuse, what they offer
// masked and what they let through, first on their own and then through the screen. No
// explicit imagery is used: the refused picture is a harmless drawing checked at a threshold
// of zero.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
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
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.media.Vision.Tag;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.ui.chat.ChatActivity;
import com.example.aikeyboard.ui.chat.ChatChecks;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;
import com.example.aikeyboard.ui.chat.KidsDrawView;
import com.example.aikeyboard.ui.chat.LockChipSpan;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class KidsChatTest {

    private static final String TAG = "KidsChat";
    private static final long RUN_MS = 60_000;
    private static final String THREAT = "I will find you and hurt you, you worthless idiot";
    private static final String WITH_EMAIL = "My email is mia@example.com";
    private static final String MASKED_EMAIL = "My email is [email]";
    // "After school today I went to the park to play football", in Hindi.
    private static final String IN_HINDI = "आज स्कूल के बाद मैं पार्क में फुटबॉल खेलने गया था";

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
    // Set by the tests that speak: known speech stands where the microphone would be.
    private AudioCaptureController realMicrophone;
    private FakeCapture microphone;
    // The speech model is slow to load the first time; nothing else waits this long.
    private static final long WRITING_MS = 3 * 60_000;

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
        if (realMicrophone != null) app.capture = realMicrophone;
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

    @Test
    public void unkindWordsAnywhereInTheLongestMessageAreStillRefused() throws Exception {
        String half = padded("", ChatChecks.MESSAGE_LIMIT / 2 - THREAT.length());

        assertEquals("at the very end", Outcome.UNKIND,
                check(padded(THREAT, ChatChecks.MESSAGE_LIMIT)).outcome);
        assertEquals("in the middle", Outcome.UNKIND,
                check(half + THREAT + ". " + half).outcome);
    }

    @Test
    public void personalDetailsAtTheEndOfTheLongestMessageAreStillFound() throws Exception {
        TextVerdict verdict = check(padded(WITH_EMAIL, ChatChecks.MESSAGE_LIMIT));

        assertEquals(Outcome.PERSONAL, verdict.outcome);
        assertTrue(verdict.masked, verdict.masked.endsWith(MASKED_EMAIL));
    }

    @Test
    public void aMessageInHindiIsToldApartAndNotSent() throws Exception {
        assertEquals(Outcome.OTHER_LANGUAGE, check(IN_HINDI).outcome);
    }

    // English that the language model is sure is Catalan, Indonesian, Tagalog, Albanian,
    // Afrikaans or Swahili: each of these was turned away with "I can only read English".
    @Test
    public void englishTheLanguageModelMistakesIsNotTurnedAwayForItsLanguage() throws Exception {
        String[] mistaken = {
                "Yes! I love football",
                "No! I love football",
                "Yes! I love dinosaurs",
                "Woah! Thanks Kabir",
                "Hooray, happy Diwali!",
                "Hi Aarav and hello. I love dinosaurs",
                "Uh oh! Sara! I am here",
                "Omg!! I lost my helmet",
                "Kabir!! lets do rock paper scissors haha",
                "Ayy mia n leo r here bro",
        };
        for (String message : mistaken) {
            assertFalse(message, check(message).outcome == Outcome.OTHER_LANGUAGE);
        }
    }

    @Test
    public void thePictureModelSeesNobodyInADrawingOfAHouse() throws Exception {
        TestModels.ensureInstalled("eye");
        Bitmap picture = VisionModelsTest.harmlessPicture();

        ChatChecks.Seen seen = onInferenceThread(() -> checks.look(picture));

        Log.i(TAG, "eye on the drawn house: person=" + seen.person + " words=" + seen.words);
        assertFalse("a drawing of a house was taken for a photo of a person", seen.person);
    }

    // ---- The screen ----

    @Test
    public void theFieldTakesNoMoreThanTheChecksWereTriedAt() {
        openChat();

        onView(withId(R.id.chat_input)).perform(
                replaceText(padded("", 3 * ChatChecks.MESSAGE_LIMIT)));

        assertEquals(ChatChecks.MESSAGE_LIMIT, shown(R.id.chat_input).length());
    }

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
        assertEquals("what was typed stays in the field", WITH_EMAIL, shown(R.id.chat_input));
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
    public void theChildWhoseTurnItIsIsNamedAndHasTheBigFace() {
        openChat();
        assertEquals("Mia's turn", shown(R.id.chat_turn));
        assertTrue("Mia's face should be the big one",
                width(R.id.chat_user_one) > width(R.id.chat_user_two));

        onView(withId(R.id.chat_user_two)).perform(click());
        getInstrumentation().waitForIdleSync();

        assertEquals("Leo's turn", shown(R.id.chat_turn));
        assertTrue("Leo's face should be the big one",
                width(R.id.chat_user_two) > width(R.id.chat_user_one));
    }

    @Test
    public void aRefusalComesAsATipThatSaysWhatToDo() throws Exception {
        openChat();

        say(THREAT);

        assertTrue(TestModels.await(() -> shown(R.id.chat_notice).equals(
                context.getString(R.string.chat_unkind)), RUN_MS));
        assertEquals(context.getString(R.string.chat_unkind_more),
                shown(R.id.chat_notice_more));
    }

    @Test
    public void aHiddenDetailIsDrawnAsALockChipInTheChat() throws Exception {
        openChat();
        sayWithPersonalDetails();

        onView(withId(R.id.chat_send_masked)).perform(click());

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals("the words themselves stay plain", MASKED_EMAIL, bubble(0));
        assertEquals("one chip, over the hidden word", 1, chips(0));
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

    @Test
    public void aHarmlessPictureIsSentAsAPicture() throws Exception {
        openChat();

        pick(Uri.fromFile(harmless()));

        assertTrue("not sent; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> sent() == 1, RUN_MS));
        assertTrue("the message shows no picture", showsPicture(0));
    }

    @Test
    public void aPictureTheCheckRefusesIsNotSentAndTheSenderIsTold() throws Exception {
        openChat();
        // Nothing explicit is shown to the model: at a threshold of zero any picture counts.
        ChatChecks atZero = ChatChecks.with(app, context, new AppSettings(1, Map.of(
                AppSettings.modelSettingKey("moderator", ModelRegistry.MODERATOR_THRESHOLD), 0)));
        use(atZero);

        pick(Uri.fromFile(harmless()));

        assertTrue("no warning; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(
                        context.getString(R.string.chat_picture_refused)), RUN_MS));
        assertEquals(0, sent());
    }

    @Test
    public void aFileThatIsNotAPictureIsNotSent() throws Exception {
        File notes = new File(context.getCacheDir(), "notes.txt");
        Files.write(notes.toPath(), "not a picture".getBytes(StandardCharsets.UTF_8));
        openChat();

        pick(Uri.fromFile(notes));

        assertTrue("no warning; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(
                        context.getString(R.string.chat_picture_unreadable)), RUN_MS));
        assertEquals(0, sent());
    }

    @Test
    public void theCardForTheLongestMessageCanStillBeAnswered() throws Exception {
        openChat();
        say(padded(WITH_EMAIL, ChatChecks.MESSAGE_LIMIT));
        assertTrue("no card; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_personal_masked).endsWith(MASKED_EMAIL), RUN_MS));

        // The message is shown twice above the buttons, so on a phone they start off screen.
        onView(withId(R.id.chat_change)).perform(scrollTo(), click());

        onView(withId(R.id.chat_personal)).check(matches(not(isDisplayed())));
        onView(withId(R.id.chat_input)).check(matches(isEnabled()));
    }

    @Test
    public void aMessageThatCouldNotBeCheckedIsNotSentAndTheSenderIsTold() throws Exception {
        openChat();
        Stub<String, ToxicScores> toxic = new Stub<>("toxic", null);
        toxic.fail = true;
        use(new ChatChecks(toxic, app.redact, app.moderator, app.tongue, app.eye, Set.of(),
                ChatChecks.namedThings(context), ChatChecks.kidRules()));

        say("See you tomorrow");

        assertTrue("no warning; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(
                        context.getString(R.string.chat_unchecked)), RUN_MS));
        assertEquals(0, sent());
        assertEquals("See you tomorrow", shown(R.id.chat_input));
    }

    @Test
    public void aPictureThatCouldNotBeCheckedIsNotSent() throws Exception {
        openChat();
        Stub<Bitmap, Sensitivity> moderator = new Stub<>("moderator", null);
        moderator.fail = true;
        use(new ChatChecks(app.toxic, app.redact, moderator, app.tongue, app.eye, Set.of(),
                ChatChecks.namedThings(context), ChatChecks.kidRules()));

        pick(Uri.fromFile(harmless()));

        assertTrue("no warning; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(
                        context.getString(R.string.chat_picture_refused)), RUN_MS));
        assertEquals(0, sent());
    }

    @Test
    public void whatIsSentIsWhatWasCheckedWhateverIsTypedMeanwhile() throws Exception {
        openChat();
        Stub<String, ToxicScores> toxic = new Stub<>("toxic", new ToxicScores(
                Map.of("hateful", 0.0, "abusive", 0.0, "threat", 0.0),
                Map.of("hateful", 0.5, "abusive", 0.5, "threat", 0.5)));
        toxic.hold = new CountDownLatch(1);
        use(new ChatChecks(toxic, app.redact, app.moderator, app.tongue, app.eye, Set.of(),
                ChatChecks.namedThings(context), ChatChecks.kidRules()));

        say("See you tomorrow");
        // The check is still running: the field is open and something else is typed.
        onView(withId(R.id.chat_input)).perform(replaceText("something that was never checked"));
        toxic.hold.countDown();

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals("See you tomorrow", bubble(0));
    }

    @Test
    public void looseningTheKeyboardsSettingsDoesNotLoosenTheChat() throws Exception {
        String emails = AppSettings.modelSettingKey("redact", "cat_email");
        app.settings.setBoolean(emails, false);
        try {
            assertTrue(TestModels.await(() -> !app.settings.latest().bool(emails, true), 5000));
            openChat();

            say(WITH_EMAIL);

            assertTrue("an email address went unnoticed; notice: " + shown(R.id.chat_notice),
                    TestModels.await(() -> shown(R.id.chat_personal_masked).equals(MASKED_EMAIL),
                            RUN_MS));
        } finally {
            app.settings.setBoolean(emails, true);
        }
    }

    @Test
    public void theLockedChatLeadsToTheModelsScreenWhereTheReasonIsShown() throws Exception {
        assumeTrue("needs network to put the model back", TestModels.online());
        app.models.delete("moderator");
        try {
            assertTrue(TestModels.awaitStatus("moderator",
                    status -> status.state == AssetState.NOT_INSTALLED, 30_000));
            openChat();

            onView(withId(R.id.chat_models)).perform(click());

            onView(withId(R.id.models_heading)).check(matches(isDisplayed()));
        } finally {
            TestModels.ensureInstalled("moderator");
        }
    }

    @Test
    public void aMessageInAnotherLanguageIsNotSentAndTheHelperSaysWhy() throws Exception {
        openChat();

        say(IN_HINDI);

        assertTrue("no tip; notice: " + shown(R.id.chat_notice), TestModels.await(
                () -> shown(R.id.chat_notice).equals(
                        context.getString(R.string.chat_other_language)), RUN_MS));
        assertEquals(context.getString(R.string.chat_other_language_more),
                shown(R.id.chat_notice_more));
        assertEquals(0, sent());
        assertEquals(IN_HINDI, shown(R.id.chat_input));
    }

    @Test
    public void emojiIdeasFollowWhatIsTypedAndATapAddsOneWithoutSending() throws Exception {
        TestModels.ensureInstalled("emo");
        openChat();
        String typed = "I love playing football with my friends";

        onView(withId(R.id.chat_input)).perform(replaceText(typed));

        assertTrue("no emoji ideas appeared",
                TestModels.await(() -> !shown(R.id.chat_idea_one).isEmpty(), RUN_MS));
        String idea = shown(R.id.chat_idea_one);
        onView(withId(R.id.chat_idea_one)).perform(click());
        assertEquals(typed + idea, shown(R.id.chat_input));
        assertEquals("a tap on an idea sends nothing", 0, sent());
    }

    @Test
    public void aDrawingIsTidiedAndSentLikeAPicture() throws Exception {
        TestModels.ensureInstalled("shapes");
        openChat();
        onView(withId(R.id.chat_draw)).perform(click());
        onView(withId(R.id.chat_pad)).check(matches(isDisplayed()));
        onView(withId(R.id.chat_send_drawing)).check(matches(not(isEnabled())));

        drawACircle();

        String tidied = context.getString(R.string.chat_pad_tidied,
                context.getString(R.string.chat_shape_round));
        assertTrue("the circle was not tidied; hint: " + shown(R.id.chat_pad_hint),
                TestModels.await(() -> shown(R.id.chat_pad_hint).equals(tidied), RUN_MS));
        onView(withId(R.id.chat_send_drawing)).perform(click());

        assertTrue("not sent; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> sent() == 1, RUN_MS));
        assertTrue("the message shows no picture", showsPicture(0));
        onView(withId(R.id.chat_pad)).check(matches(not(isDisplayed())));
        onView(withId(R.id.chat_input)).check(matches(isDisplayed()));
    }

    @Test
    public void undoTakesOffTheLastStrokeAndStartAgainEmptiesThePad() {
        openChat();
        onView(withId(R.id.chat_draw)).perform(click());
        drawALine(0.2f);
        drawALine(0.6f);
        assertEquals(2, strokes());

        onView(withId(R.id.chat_undo)).perform(click());
        assertEquals(1, strokes());

        onView(withId(R.id.chat_clear)).perform(click());
        assertEquals(0, strokes());
        onView(withId(R.id.chat_send_drawing)).check(matches(not(isEnabled())));
    }

    @Test
    public void gettingReadyAlsoFetchesTheEmojiAndShapeModels() throws Exception {
        assumeTrue("needs network to put the models back", TestModels.online());
        app.models.delete("moderator");
        app.models.delete("shapes");
        try {
            assertTrue(TestModels.awaitStatus("shapes",
                    status -> status.state == AssetState.NOT_INSTALLED, 30_000));
            assertTrue(TestModels.awaitStatus("moderator",
                    status -> status.state == AssetState.NOT_INSTALLED, 30_000));
            openChat();

            onView(withId(R.id.chat_download)).perform(click());

            assertTrue("the shape model was not fetched", TestModels.awaitStatus("shapes",
                    status -> status.state == AssetState.READY, RUN_MS));
        } finally {
            TestModels.ensureInstalled("moderator");
            TestModels.ensureInstalled("shapes");
        }
    }

    @Test
    public void aPhotoThatShowsAPersonRaisesACardAndNothingIsSent() throws Exception {
        openChat();
        use(seeing(findings(1)));

        pick(Uri.fromFile(harmless()));

        assertTrue("no card; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> isShown(R.id.chat_people), RUN_MS));
        assertEquals(0, sent());
        onView(withId(R.id.chat_send)).check(matches(not(isEnabled())));
    }

    @Test
    public void sendItAnywaySendsThePhotoThatShowsAPerson() throws Exception {
        openChat();
        use(seeing(findings(1)));
        pick(Uri.fromFile(harmless()));
        assertTrue(TestModels.await(() -> isShown(R.id.chat_people), RUN_MS));

        onView(withId(R.id.chat_send_photo)).perform(scrollTo(), click());

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        assertTrue(showsPicture(0));
        assertFalse(isShown(R.id.chat_people));
    }

    @Test
    public void dontSendItDropsThePhotoAndGivesTheChatBack() throws Exception {
        openChat();
        use(seeing(findings(1)));
        pick(Uri.fromFile(harmless()));
        assertTrue(TestModels.await(() -> isShown(R.id.chat_people), RUN_MS));

        onView(withId(R.id.chat_drop_photo)).perform(scrollTo(), click());

        assertFalse(isShown(R.id.chat_people));
        assertEquals(0, sent());
        onView(withId(R.id.chat_input)).check(matches(isEnabled()));
    }

    @Test
    public void aSentPhotoSaysWhatTheAppCanSeeInIt() throws Exception {
        openChat();
        use(seeing(findings(0, "beach", "sea", "boat")));

        pick(Uri.fromFile(harmless()));

        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        String[] seen = {""};
        chat.onActivity(activity -> seen[0] = ((TextView) row(activity, 0)
                .findViewById(R.id.chat_bubble_seen)).getText().toString());
        assertEquals("I can see: beach, sea, boat", seen[0]);
    }

    @Test
    public void aDrawingFromThePadIsNotLookedAtForPeople() throws Exception {
        openChat();
        use(seeing(findings(1)));
        onView(withId(R.id.chat_draw)).perform(click());
        drawALine(0.5f);

        onView(withId(R.id.chat_send_drawing)).perform(click());

        assertTrue("the drawing was held back; card up: " + isShown(R.id.chat_people),
                TestModels.await(() -> sent() == 1, RUN_MS));
    }

    // Not a check: saves a picture of the card for a photo that shows a person, which cannot
    // be reached by hand without such a photo. Run with -e chatshots true.
    @Test
    public void aPictureOfThePeopleCardForTheRecord() throws Exception {
        assumeTrue("opt-in: pass -e chatshots true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatshots")));
        openChat();
        say("Show me your new bike!");
        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        onView(withId(R.id.chat_user_two)).perform(click());
        use(seeing(findings(1)));
        pick(Uri.fromFile(harmless()));
        assertTrue(TestModels.await(() -> isShown(R.id.chat_people), RUN_MS));
        Thread.sleep(700);
        File dir = new File(context.getExternalFilesDir(null), "tour");
        dir.mkdirs();
        assertTrue(UiDevice.getInstance(getInstrumentation())
                .takeScreenshot(new File(dir, "kids_chat_people_card.png")));
    }

    @Test
    public void theSendButtonIsAMicrophoneOnlyWhileNothingIsTyped() throws Exception {
        TestModels.ensureInstalled("voz");
        openChat();
        assertEquals(context.getString(R.string.chat_speak), described(R.id.chat_send));

        onView(withId(R.id.chat_input)).perform(replaceText("hi"));
        assertEquals(context.getString(R.string.chat_send), described(R.id.chat_send));

        onView(withId(R.id.chat_input)).perform(replaceText(""));
        assertEquals(context.getString(R.string.chat_speak), described(R.id.chat_send));
    }

    @Test
    public void aSpokenMessageIsShownAsWordsFirstAndThenSentMarkedAsSpoken() throws Exception {
        speakInto();
        openChat();
        sayOutLoud();
        String words = shown(R.id.chat_heard_words);
        assertTrue("heard: " + words, TestSpeech.recognised(words));
        assertEquals("nothing is sent before the child says so", 0, sent());

        onView(withId(R.id.chat_heard_send)).perform(scrollTo(), click());

        assertTrue("not sent; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> sent() == 1, RUN_MS));
        assertEquals(words, bubble(0));
        assertTrue("the message is not marked as spoken", markedAsSpoken(0));
        assertEquals("the field is emptied once the message has gone", "",
                shown(R.id.chat_input));
    }

    @Test
    public void sayItAgainListensAgainAndSendsNothing() throws Exception {
        speakInto();
        openChat();
        sayOutLoud();

        onView(withId(R.id.chat_heard_again)).perform(scrollTo(), click());

        assertTrue("not listening again",
                TestModels.await(() -> isShown(R.id.chat_listen), RUN_MS));
        assertFalse(isShown(R.id.chat_heard));
        assertEquals(0, sent());
    }

    @Test
    public void dontSendItThrowsAwayWhatWasHeard() throws Exception {
        speakInto();
        openChat();
        sayOutLoud();

        onView(withId(R.id.chat_heard_drop)).perform(scrollTo(), click());

        assertFalse(isShown(R.id.chat_heard));
        assertEquals(0, sent());
        assertEquals("", shown(R.id.chat_input));
        onView(withId(R.id.chat_input)).check(matches(isEnabled()));
    }

    @Test
    public void stoppingWhileListeningThrowsTheRecordingAway() throws Exception {
        speakInto();
        openChat();
        onView(withId(R.id.chat_send)).perform(click());
        assertTrue(TestModels.await(() -> isShown(R.id.chat_listen), RUN_MS));

        onView(withId(R.id.chat_listen_cancel)).perform(click());

        assertFalse(isShown(R.id.chat_listen));
        assertTrue("the recording was kept", microphone.cancelled);
        onView(withId(R.id.chat_input)).check(matches(isDisplayed()));
        assertEquals(0, sent());
    }

    // Not a check: saves pictures of a spoken message on its way, which cannot be made by
    // hand on an emulator, whose microphone hears only silence. Run with -e chatshots true.
    @Test
    public void picturesOfASpokenMessageForTheRecord() throws Exception {
        assumeTrue("opt-in: pass -e chatshots true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatshots")));
        speakInto();
        openChat();
        onView(withId(R.id.chat_user_two)).perform(click());
        say("What are you doing today?");
        assertTrue(TestModels.await(() -> sent() == 1, RUN_MS));
        onView(withId(R.id.chat_user_one)).perform(click());
        UiDevice device = UiDevice.getInstance(getInstrumentation());
        File dir = new File(context.getExternalFilesDir(null), "tour");
        dir.mkdirs();

        onView(withId(R.id.chat_send)).perform(click());
        assertTrue(TestModels.await(() -> isShown(R.id.chat_listen), RUN_MS));
        Thread.sleep(1500);
        assertTrue(device.takeScreenshot(new File(dir, "kids_chat_listening.png")));

        onView(withId(R.id.chat_listen_done)).perform(click());
        assertTrue(TestModels.await(() -> isShown(R.id.chat_heard), WRITING_MS));
        Thread.sleep(700);
        assertTrue(device.takeScreenshot(new File(dir, "kids_chat_heard.png")));

        onView(withId(R.id.chat_heard_send)).perform(scrollTo(), click());
        assertTrue(TestModels.await(() -> sent() == 2, RUN_MS));
        Thread.sleep(700);
        assertTrue(device.takeScreenshot(new File(dir, "kids_chat_spoken.png")));
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

    /** A finger drawing a circle in the middle of the pad. */
    private void drawACircle() {
        chat.onActivity(activity -> {
            View pad = activity.findViewById(R.id.chat_canvas);
            float radius = Math.min(pad.getWidth(), pad.getHeight()) * 0.3f;
            float[][] points = new float[49][];
            for (int i = 0; i <= 48; i++) {
                double angle = 2 * Math.PI * i / 48;
                points[i] = new float[] {pad.getWidth() / 2f + radius * (float) Math.cos(angle),
                        pad.getHeight() / 2f + radius * (float) Math.sin(angle)};
            }
            touch(pad, points);
        });
    }

    /** A finger drawing a line across the pad, at a height given as a share of the pad's. */
    private void drawALine(float down) {
        chat.onActivity(activity -> {
            View pad = activity.findViewById(R.id.chat_canvas);
            float[][] points = new float[17][];
            for (int i = 0; i <= 16; i++) {
                points[i] = new float[] {pad.getWidth() * (0.2f + 0.6f * i / 16),
                        pad.getHeight() * down};
            }
            touch(pad, points);
        });
    }

    private static void touch(View view, float[][] points) {
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < points.length; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN
                    : i == points.length - 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, start + i * 10L, action,
                    points[i][0], points[i][1], 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private int strokes() {
        int[] count = {0};
        chat.onActivity(activity -> count[0] =
                ((KidsDrawView) activity.findViewById(R.id.chat_canvas)).strokeCount());
        return count[0];
    }

    private int width(int viewId) {
        int[] width = {0};
        chat.onActivity(activity -> width[0] = activity.findViewById(viewId).getWidth());
        return width[0];
    }

    /** How many hidden details the message at a position draws as lock chips. */
    private int chips(int position) {
        int[] count = {0};
        chat.onActivity(activity -> {
            CharSequence text = ((TextView) row(activity, position)
                    .findViewById(R.id.chat_bubble_text)).getText();
            if (text instanceof Spanned) {
                count[0] = ((Spanned) text).getSpans(0, text.length(), LockChipSpan.class).length;
            }
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

    /** Puts known speech where the microphone would be, with the speech model installed. */
    private void speakInto() throws Exception {
        AudioClip speech = TestSpeech.english();
        assumeTrue("this device has no speech synthesiser to make the test speech",
                speech != null);
        TestModels.ensureInstalled("voz");
        TestModels.allowMicrophone();
        realMicrophone = app.capture;
        microphone = new FakeCapture(speech);
        app.capture = microphone;
    }

    /** Taps the microphone, "speaks", taps Done, and waits for the words to come up. */
    private void sayOutLoud() throws InterruptedException {
        onView(withId(R.id.chat_send)).perform(click());
        assertTrue("not listening; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> isShown(R.id.chat_listen), RUN_MS));
        onView(withId(R.id.chat_listen_done)).perform(click());
        assertTrue("nothing was written down; notice: " + shown(R.id.chat_notice),
                TestModels.await(() -> isShown(R.id.chat_heard), WRITING_MS));
    }

    private String described(int viewId) {
        String[] label = {""};
        chat.onActivity(activity -> label[0] =
                String.valueOf(activity.findViewById(viewId).getContentDescription()));
        return label[0];
    }

    /** Whether the message at a position carries the small microphone of a spoken one. */
    private boolean markedAsSpoken(int position) {
        boolean[] marked = {false};
        chat.onActivity(activity -> marked[0] = ((TextView) row(activity, position)
                .findViewById(R.id.chat_bubble_text)).getCompoundDrawablesRelative()[0] != null);
        return marked[0];
    }

    /** The chat's real checks, with a picture model that reports what it is told to. */
    private ChatChecks seeing(ImageFindings told) {
        return new ChatChecks(app.toxic, app.redact, app.moderator, app.tongue,
                new Stub<>("eye", told), Set.of(), ChatChecks.namedThings(context),
                ChatChecks.kidRules());
    }

    private static ImageFindings findings(int people, String... tags) {
        List<Tag> surestFirst = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) surestFirst.add(new Tag(tags[i], 0.9 - 0.1 * i));
        return new ImageFindings("photo", surestFirst, 0.5, people, 0, 0);
    }

    private boolean isShown(int viewId) {
        boolean[] shown = {false};
        chat.onActivity(activity -> shown[0] = activity.findViewById(viewId).isShown());
        return shown[0];
    }

    /** Puts other checks behind the open chat screen, as the app itself never does. */
    private void use(ChatChecks other) {
        chat.onActivity(activity -> new ViewModelProvider(activity)
                .get(ChatActivity.Session.class).checks = other);
    }

    /** A model that answers as told, fails, or waits until it is let go. */
    static final class Stub<I, O> implements ModelAdapter<I, O> {
        private final String id;
        private final O result;
        boolean fail;
        CountDownLatch hold;

        Stub(String id, O result) {
            this.id = id;
            this.result = result;
        }

        @Override
        public String modelId() {
            return id;
        }

        @Override
        public O run(I input, AppSettings settings) throws Exception {
            if (hold != null) hold.await(30, TimeUnit.SECONDS);
            if (fail) throw new IllegalStateException(id + " is not available");
            return result;
        }

        @Override
        public void release() {}
    }

    private void pick(Uri uri) {
        chat.onActivity(activity -> activity.onPicturePicked(uri));
    }

    private File harmless() throws Exception {
        File file = new File(context.getCacheDir(), "house.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            VisionModelsTest.harmlessPicture().compress(Bitmap.CompressFormat.PNG, 90, out);
        }
        return file;
    }

    private boolean showsPicture(int position) {
        boolean[] shows = {false};
        chat.onActivity(activity -> {
            ImageView picture = row(activity, position).findViewById(R.id.chat_bubble_picture);
            shows[0] = picture.isShown() && picture.getDrawable() != null;
        });
        return shows[0];
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

    // Measurement, not a check: how far into a long message the two text models still see.
    // The unkind words, or the email address, sit at the very end. Run with -e chatprobe true.
    @Test
    public void howFarIntoALongMessageTheChecksSee() throws Exception {
        assumeTrue("measurement only: pass -e chatprobe true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatprobe")));
        for (int length : new int[] {250, 500, 1000, 2000, 4000}) {
            String line = "threat at the end=" + check(padded(THREAT, length)).outcome
                    + " email at the end=" + check(padded(WITH_EMAIL, length)).outcome;
            Log.i(TAG, "length " + length + ": " + line);
            Bundle status = new Bundle();
            status.putString("chat.length" + length, line);
            getInstrumentation().sendStatus(0, status);
        }
    }

    // Measurement, not a check: whether naming a place raises the personal-details card.
    // Addresses are watched, and the model's address labels include city and state.
    @Test
    public void whatThePersonalDetailsCheckMakesOfPlaces() throws Exception {
        assumeTrue("measurement only: pass -e chatprobe true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatprobe")));
        String[] places = {
                "We went to London on Saturday",
                "I live in Mumbai",
                "My house is 12 Park Street",
                "Let's meet at the park after school",
        };
        for (int i = 0; i < places.length; i++) {
            TextVerdict verdict = check(places[i]);
            String line = verdict.outcome + (verdict.masked == null ? "" : " " + verdict.masked);
            Log.i(TAG, "place " + (i + 1) + ": " + line);
            Bundle status = new Bundle();
            status.putString("chat.place" + (i + 1), line);
            getInstrumentation().sendStatus(0, status);
        }
    }

    // Measurement, not a check: which messages the language rule stops. Run with
    // -e chatprobe true.
    @Test
    public void whatTheLanguageRuleMakesOfTheSamples() throws Exception {
        assumeTrue("measurement only: pass -e chatprobe true", "true".equals(
                InstrumentationRegistry.getArguments().getString("chatprobe")));
        String[] samples = {
                "Do you want to play football after school?",
                "ok",
                "Hahaha",
                "Hi Leo",
                "Pizza party at Aarav's house",
                IN_HINDI,
                "Aaj school ke baad park mein football khelne chalein?",
                "Kal milte hain, bye",
                "Tu pagal hai kya",
                "Je suis allé au parc avec mes amis après l'école",
                "Hola amigo, ¿quieres jugar al fútbol después de la escuela?",
                "Guten Morgen",
        };
        for (int i = 0; i < samples.length; i++) {
            String sample = samples[i];
            LanguageHint hint = app.tongue.run(sample, ChatChecks.kidRules());
            String line = "language=" + hint.language + " sure=" + hint.reliable
                    + " chat=" + check(sample).outcome;
            Log.i(TAG, "language " + (i + 1) + ": " + line);
            Bundle status = new Bundle();
            status.putString("chat.language" + (i + 1), line);
            getInstrumentation().sendStatus(0, status);
        }
    }

    /** A friendly message of about the given length that ends in the given words. */
    private static String padded(String ending, int length) {
        String filler = "We had so much fun at the park today. ";
        StringBuilder message = new StringBuilder();
        while (message.length() + filler.length() + ending.length() <= length) {
            message.append(filler);
        }
        return message.append(ending).toString();
    }

    private TextVerdict check(String text) throws Exception {
        return onInferenceThread(() -> checks.text(text));
    }

    private <T> T onInferenceThread(Callable<T> work) throws Exception {
        return app.inference.submit(work).get(5, TimeUnit.MINUTES);
    }
}

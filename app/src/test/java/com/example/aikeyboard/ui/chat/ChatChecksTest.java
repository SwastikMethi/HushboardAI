// The kids' chat sending rules, with stand-in models: what is refused, what is offered
// masked, and that nothing goes out when a check could not run.
package com.example.aikeyboard.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.RedactCategories;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.media.Vision.Tag;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatChecksTest {

    private static final String WITH_EMAIL = "My email is mia@example.com";

    private final Stub<String, ToxicScores> toxic = new Stub<>("toxic", tone(0.01));
    private final Stub<String, List<SensitiveSpan>> redact = new Stub<>("redact", List.of());
    private final Stub<Bitmap, Sensitivity> moderator =
            new Stub<>("moderator", new Sensitivity(0.02, false));
    private final Stub<String, LanguageHint> tongue =
            new Stub<>("tongue", new LanguageHint("en", true));
    private final Stub<Bitmap, ImageFindings> eye = new Stub<>("eye", seeing(0, 0, 0));
    private final ChatChecks checks = new ChatChecks(toxic, redact, moderator, tongue, eye,
            Set.of("darn"), Map.of("beach", "beach", "sea", "sea", "palm_tree", "palm tree",
                    "sky", "sky", "cat", "cat", "black_cat", "cat"), ChatChecks.kidRules());

    @Test
    public void aPersonInAPhotoIsNoticed() {
        eye.result = seeing(1, 0, 0);

        assertTrue(checks.look(null).person);
    }

    @Test
    public void aFaceAloneCountsAsAPerson() {
        eye.result = seeing(0, 0, 1);

        assertTrue(checks.look(null).person);
    }

    @Test
    public void aPetIsNotAPerson() {
        eye.result = seeing(0, 2, 0);

        assertFalse(checks.look(null).person);
    }

    @Test
    public void aPhotoIsDescribedInTheThreeThingsTheModelIsSurestOf() {
        eye.result = seeing(0, 0, 0, "beach", "sea", "palm_tree", "sky");

        assertEquals(List.of("beach", "sea", "palm tree"), checks.look(null).words);
    }

    @Test
    public void onlyThingsOnTheChatsOwnListAreNamed() {
        eye.result = seeing(0, 0, 0, "sharp", "beach", "screenshot", "alcoholic_beverage", "sea");

        assertEquals(List.of("beach", "sea"), checks.look(null).words);
    }

    @Test
    public void twoLabelsForTheSameThingAreSaidOnce() {
        eye.result = seeing(0, 0, 0, "black_cat", "cat", "sky");

        assertEquals(List.of("cat", "sky"), checks.look(null).words);
    }

    @Test
    public void withoutThePictureModelNothingIsSeenAndNobodyIsAssumed() {
        eye.fail = true;

        assertFalse(checks.look(null).person);
        assertEquals(List.of(), checks.look(null).words);
    }

    @Test
    public void aMessageThatIsSurelyNotEnglishIsNotSent() {
        tongue.result = new LanguageHint("hi", true);

        assertEquals(Outcome.OTHER_LANGUAGE, checks.text("बहुत सुंदर घर है").outcome);
    }

    @Test
    public void englishTheLanguageModelIsSureIsAnotherLanguageIsStillSent() {
        // What the real model says of this message: Catalan, and sure of it.
        tongue.result = new LanguageHint("ca", true);

        assertEquals(Outcome.CLEAR, checks.text("Yes! I love football ⚽").outcome);
    }

    @Test
    public void oneWordInAnotherScriptLeavesTheLanguageModelItsSay() {
        tongue.result = new LanguageHint("hi", true);

        assertEquals(Outcome.OTHER_LANGUAGE, checks.text("I love नमस्ते").outcome);
    }

    @Test
    public void aGuessAtAnotherLanguageIsNotEnoughToRefuseAMessage() {
        tongue.result = new LanguageHint("fr", false);

        assertEquals(Outcome.CLEAR, checks.text("Bonjour Leo").outcome);
    }

    @Test
    public void aListedWordIsRefusedWhateverTheLanguage() {
        tongue.result = new LanguageHint("fr", true);

        assertEquals(Outcome.UNKIND, checks.text("darn, c'est dommage").outcome);
    }

    @Test
    public void aMessageWhoseLanguageCouldNotBeToldIsNotSent() {
        tongue.fail = true;

        assertEquals(Outcome.UNCHECKED, checks.text("see you after school").outcome);
    }

    @Test
    public void aListedWordIsRefusedWhateverItsCaseOrPunctuation() {
        assertEquals(Outcome.UNKIND, checks.text("Oh DARN!").outcome);
    }

    @Test
    public void aListedWordInsideALongerWordIsNotAHit() {
        assertEquals(Outcome.CLEAR, checks.text("Darnell is here").outcome);
    }

    @Test
    public void wordingTheToneModelFlagsIsRefused() {
        toxic.result = tone(0.9);

        assertEquals(Outcome.UNKIND, checks.text("you are worthless").outcome);
    }

    @Test
    public void theKidRulesFlagWordingBelowTheModelsOwnThreshold() {
        toxic.result = tone(0.45); // the model's own line is 0.5

        assertEquals(Outcome.UNKIND, checks.text("nobody likes you").outcome);
    }

    @Test
    public void personalDetailsAreOfferedMasked() {
        redact.result = List.of(emailSpan());

        TextVerdict verdict = checks.text(WITH_EMAIL);

        assertEquals(Outcome.PERSONAL, verdict.outcome);
        assertEquals("My email is [email]", verdict.masked);
    }

    @Test
    public void aStreetAndItsNumberAreHiddenAsOneAddress() {
        redact.result = List.of(
                new SensitiveSpan("addresses", 12, 14, "12", "[BUILDING_NUMBER_1]"),
                new SensitiveSpan("addresses", 15, 26, "Park Street", "[STREET_NAME_1]"));

        assertEquals("My house is [address]", checks.text("My house is 12 Park Street").masked);
    }

    @Test
    public void detailsOfDifferentKindsAreHiddenEachUnderItsOwnWord() {
        redact.result = List.of(
                new SensitiveSpan("email", 26, 41, "mia@example.com", "[EMAIL_1]"),
                new SensitiveSpan("phone", 5, 17, "415-555-0132", "[PHONE_1]"));

        assertEquals("Call [phone number] or mail [email]",
                checks.text("Call 415-555-0132 or mail mia@example.com").masked);
    }

    @Test
    public void aKindOfDetailWithNoWordOfItsOwnIsStillHidden() {
        redact.result = List.of(new SensitiveSpan("something new", 3, 9, "secret", "[X_1]"));

        assertEquals("My [hidden] is out", checks.text("My secret is out").masked);
    }

    @Test
    public void unkindWordingIsRefusedEvenWhenItAlsoHoldsPersonalDetails() {
        toxic.result = tone(0.9);
        redact.result = List.of(emailSpan());

        assertEquals(Outcome.UNKIND, checks.text(WITH_EMAIL).outcome);
    }

    @Test
    public void anOrdinaryMessageIsClear() {
        assertEquals(Outcome.CLEAR, checks.text("see you after school").outcome);
    }

    @Test
    public void aMessageTheToneModelCouldNotCheckIsNotSent() {
        toxic.fail = true;

        assertEquals(Outcome.UNCHECKED, checks.text("see you after school").outcome);
    }

    @Test
    public void aMessageThePersonalDetailsModelCouldNotCheckIsNotSent() {
        redact.fail = true;

        assertEquals(Outcome.UNCHECKED, checks.text("see you after school").outcome);
    }

    @Test
    public void aMessageLongerThanTheChecksWereTriedAtIsNotSent() {
        String longest = "a".repeat(ChatChecks.MESSAGE_LIMIT);

        assertEquals(Outcome.CLEAR, checks.text(longest).outcome);
        assertEquals(Outcome.UNCHECKED, checks.text(longest + "a").outcome);
    }

    @Test
    public void aPictureTheModelPassesMayBeSent() {
        assertTrue(checks.pictureAllowed(null));
    }

    @Test
    public void aFlaggedPictureIsRefused() {
        moderator.result = new Sensitivity(0.97, true);

        assertFalse(checks.pictureAllowed(null));
    }

    @Test
    public void aPictureThatCouldNotBeCheckedIsRefused() {
        moderator.fail = true;

        assertFalse(checks.pictureAllowed(null));
    }

    @Test
    public void theKidRulesWatchAddressesAndLinksButNotNames() {
        Set<String> watched = RedactCategories.labels(ChatChecks.kidRules());

        assertTrue(watched.containsAll(Set.of("EMAIL", "PHONE", "STREET_NAME", "URL")));
        assertFalse(watched.contains("GIVEN_NAME"));
    }

    private static ToxicScores tone(double abusive) {
        return new ToxicScores(Map.of("hateful", 0.01, "abusive", abusive, "threat", 0.01),
                Map.of("hateful", 0.5, "abusive", 0.5, "threat", 0.5));
    }

    /** What the picture model reports, with its tags surest first. */
    private static ImageFindings seeing(int people, int pets, int faces, String... tags) {
        List<Tag> surestFirst = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) surestFirst.add(new Tag(tags[i], 0.9 - 0.1 * i));
        return new ImageFindings("photo", surestFirst, 0.5, people, pets, faces);
    }

    private static SensitiveSpan emailSpan() {
        return new SensitiveSpan("email", 12, 27, "mia@example.com", "[EMAIL_1]");
    }

    private static final class Stub<I, O> implements ModelAdapter<I, O> {
        private final String id;
        O result;
        boolean fail;

        Stub(String id, O result) {
            this.id = id;
            this.result = result;
        }

        @Override
        public String modelId() {
            return id;
        }

        @Override
        public O run(I input, AppSettings settings) {
            if (fail) throw new IllegalStateException(id + " is not available");
            return result;
        }

        @Override
        public void release() {}
    }
}

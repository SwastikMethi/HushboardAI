// The kids' chat sending rules, with stand-in models: what is refused, what is offered
// masked, and that nothing goes out when a check could not run.
package com.example.aikeyboard.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.RedactCategories;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatChecksTest {

    private static final String WITH_EMAIL = "My email is mia@example.com";

    private final Stub<String, ToxicScores> toxic = new Stub<>("toxic", tone(0.01));
    private final Stub<String, List<SensitiveSpan>> redact = new Stub<>("redact", List.of());
    private final Stub<Bitmap, Sensitivity> moderator =
            new Stub<>("moderator", new Sensitivity(0.02, false));
    private final ChatChecks checks = new ChatChecks(toxic, redact, moderator,
            Set.of("darn"), ChatChecks.kidRules());

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
        assertEquals("My email is [EMAIL_1]", verdict.masked);
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

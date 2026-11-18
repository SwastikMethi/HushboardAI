// The kids' chat on a device, with the real models: what its checks refuse, what they offer
// masked and what they let through. No explicit imagery is used: the refused picture is a
// harmless drawing checked at a threshold of zero.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.ai.ToxicPolicy;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.ui.chat.ChatChecks;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;

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

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("toxic");
        TestModels.ensureInstalled("redact");
        TestModels.ensureInstalled("moderator");
        checks = ChatChecks.with(app, context, ChatChecks.kidRules());
    }

    @Test
    public void aThreatIsRefused() throws Exception {
        assertEquals(Outcome.UNKIND,
                check("I will find you and hurt you, you worthless idiot").outcome);
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
        TextVerdict verdict = check("My email is mia@example.com");

        assertEquals(Outcome.PERSONAL, verdict.outcome);
        assertEquals("My email is [EMAIL_1]", verdict.masked);
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

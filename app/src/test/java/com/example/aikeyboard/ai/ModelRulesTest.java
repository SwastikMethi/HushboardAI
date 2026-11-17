// The app-side rules around Redact and Toxic: which vendor labels each user-facing category
// covers, and how the alert-frequency setting moves the model's own thresholds.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModelRulesTest {

    private static AppSettings settings(Object... keysAndValues) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new AppSettings(1, values);
    }

    private static String redact(String name) {
        return AppSettings.modelSettingKey("redact", name);
    }

    private static String toxic(String name) {
        return AppSettings.modelSettingKey("toxic", name);
    }

    @Test
    public void byDefaultEmailPhonePaymentAndIdsAreChecked_notNamesOrAddresses() {
        Set<String> labels = RedactCategories.labels(settings());
        assertTrue(labels.containsAll(Arrays.asList("EMAIL", "PHONE", "CREDIT_CARD", "SSN")));
        assertTrue(!labels.contains("GIVEN_NAME") && !labels.contains("CITY"));
    }

    @Test
    public void turningACategoryOnAddsAllOfItsLabels() {
        Set<String> labels = RedactCategories.labels(settings(redact("cat_names"), true));
        assertTrue(labels.containsAll(Arrays.asList("GIVEN_NAME", "SURNAME")));
    }

    @Test
    public void withEveryCategoryOffThereIsNothingToLookFor() {
        Set<String> labels = RedactCategories.labels(settings(
                redact("cat_email"), false, redact("cat_phone"), false,
                redact("cat_payment"), false, redact("cat_ids"), false));
        assertTrue(labels.isEmpty());
    }

    @Test
    public void everyVendorLabelBelongsToOneCategory() {
        assertEquals("email", RedactCategories.categoryOf("EMAIL"));
        assertEquals("payment", RedactCategories.categoryOf("BANK_ACCOUNT"));
        assertEquals("addresses", RedactCategories.categoryOf("ZIP_CODE"));
        assertEquals("online", RedactCategories.categoryOf("IP_ADDRESS"));
        assertNull("organisations are not offered", RedactCategories.categoryOf("ORG"));
    }

    @Test
    public void redactCoversEuropeanLanguagesOnly() {
        for (String covered : new String[] {"en", "hu", "de", "el", "bg", "nb", "is"}) {
            assertTrue(covered, RedactCategories.coversLanguage(covered));
        }
        for (String notCovered : new String[] {"ja", "hi", "zh", "ar", "ru"}) {
            assertTrue(notCovered, !RedactCategories.coversLanguage(notCovered));
        }
    }

    @Test
    public void topicsBecomeHashtagsFromTheirFixedSlugs() {
        assertEquals(" #fooddrink #travel", SuggestionPolicy.hashtags(Arrays.asList(
                new Suggestion.TopicTag("food-drink", "Food & Drink", 0.8),
                new Suggestion.TopicTag("travel", "Travel", 0.4))));
        assertEquals("", SuggestionPolicy.hashtags(List.of()));
    }

    private static final Map<String, Double> MODEL_THRESHOLDS =
            Map.of("hateful", 0.46, "abusive", 0.44, "threat", 0.35);

    @Test
    public void standardUsesTheModelsOwnThresholds() {
        Map<String, Double> scores = Map.of("hateful", 0.10, "abusive", 0.45, "threat", 0.30);
        assertEquals(List.of("abusive"),
                ToxicPolicy.flagged(scores, MODEL_THRESHOLDS, settings()));
    }

    @Test
    public void fewerAlertsNeedsAHigherScore() {
        Map<String, Double> scores = Map.of("hateful", 0.10, "abusive", 0.50, "threat", 0.30);
        assertEquals(List.of(), ToxicPolicy.flagged(scores, MODEL_THRESHOLDS,
                settings(toxic(ModelRegistry.TOXIC_SENSITIVITY), ModelRegistry.SENSITIVITY_FEWER)));
    }

    @Test
    public void moreAlertsAcceptsALowerScore() {
        Map<String, Double> scores = Map.of("hateful", 0.10, "abusive", 0.38, "threat", 0.10);
        assertEquals(List.of("abusive"), ToxicPolicy.flagged(scores, MODEL_THRESHOLDS,
                settings(toxic(ModelRegistry.TOXIC_SENSITIVITY), ModelRegistry.SENSITIVITY_MORE)));
    }

    @Test
    public void aCategoryTheUserTurnedOffIsNeverFlagged() {
        Map<String, Double> scores = Map.of("hateful", 0.90, "abusive", 0.90, "threat", 0.90);
        assertEquals(Arrays.asList("hateful", "threat"), ToxicPolicy.flagged(scores,
                MODEL_THRESHOLDS, settings(toxic("cat_abusive"), false)));
    }

    @Test
    public void aScoreTheModelDidNotReportIsNotFlagged() {
        assertEquals(List.of(), ToxicPolicy.flagged(Map.of(), MODEL_THRESHOLDS, settings()));
    }
}

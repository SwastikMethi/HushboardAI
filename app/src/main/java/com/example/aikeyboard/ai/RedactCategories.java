// Maps the categories the user chooses ("Payment details") to the vendor model's own labels.
// Only labels the Redact model really reports appear here. Pure logic.
package com.example.aikeyboard.ai;

import com.example.aikeyboard.data.AppSettings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RedactCategories {

    private static final Map<String, List<String>> LABELS = new LinkedHashMap<>();
    // Matches the defaults declared in ModelRegistry.
    private static final Set<String> ON_BY_DEFAULT =
            new HashSet<>(Arrays.asList("email", "phone", "payment", "ids"));

    static {
        LABELS.put("email", List.of("EMAIL"));
        LABELS.put("phone", List.of("PHONE"));
        LABELS.put("payment", List.of("CREDIT_CARD", "BANK_ACCOUNT", "ROUTING_NUMBER"));
        LABELS.put("ids", List.of("GOVERNMENT_ID", "PASSPORT", "DRIVERS_LICENSE", "TAX_ID",
                "SSN", "IMEI"));
        LABELS.put("names", List.of("GIVEN_NAME", "SURNAME"));
        LABELS.put("addresses", List.of("STREET_NAME", "BUILDING_NUMBER", "SECONDARY_ADDRESS",
                "CITY", "STATE", "ZIP_CODE"));
        LABELS.put("online", List.of("URL", "IP_ADDRESS"));
    }

    // The languages the vendor documents for Redact: the 24 official EU languages plus
    // Norwegian (both written forms) and Icelandic. Nothing outside this list is claimed.
    private static final Set<String> LANGUAGES = new HashSet<>(Arrays.asList(
            "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "ga", "hr", "hu", "it",
            "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv", "nb", "nn", "no", "is"));

    private RedactCategories() {}

    /** Whether Redact is documented to cover this language code. */
    public static boolean coversLanguage(String languageCode) {
        return LANGUAGES.contains(languageCode);
    }

    /** Vendor labels for every category the user has switched on. */
    public static Set<String> labels(AppSettings settings) {
        Set<String> labels = new HashSet<>();
        for (Map.Entry<String, List<String>> category : LABELS.entrySet()) {
            String key = AppSettings.modelSettingKey("redact", "cat_" + category.getKey());
            if (settings.bool(key, ON_BY_DEFAULT.contains(category.getKey()))) {
                labels.addAll(category.getValue());
            }
        }
        return labels;
    }

    /** The user-facing category a vendor label belongs to, or null if it is not offered. */
    public static String categoryOf(String vendorLabel) {
        for (Map.Entry<String, List<String>> category : LABELS.entrySet()) {
            if (category.getValue().contains(vendorLabel)) return category.getKey();
        }
        return null;
    }
}

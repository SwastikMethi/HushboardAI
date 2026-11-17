// An immutable snapshot of every saved preference. The keyboard and the companion app both
// read the same snapshot; its revision lets late AI results be matched to the settings they
// were computed under.
package com.example.aikeyboard.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class AppSettings {

    public static final String AI_ENABLED = "ai.enabled";
    public static final String BLOCKED_APPS = "ai.blocked_apps";
    public static final String OBSERVED_APPS = "ai.observed_apps";
    public static final String HAPTICS = "keyboard.haptics";
    public static final String KEY_SOUND = "keyboard.sound";
    public static final String WORD_SUGGESTIONS = "keyboard.word_suggestions";
    public static final String KEYBOARD_HEIGHT = "keyboard.height";
    public static final String UNMETERED_ONLY = "downloads.unmetered_only";

    public static final String HEIGHT_COMPACT = "compact";
    public static final String HEIGHT_STANDARD = "standard";
    public static final String HEIGHT_TALL = "tall";

    public static String modelEnabledKey(String modelId) {
        return "model." + modelId + ".enabled";
    }

    public static String modelSettingKey(String modelId, String name) {
        return "model." + modelId + "." + name;
    }

    /** Increases with every change, so "computed under older settings" is a simple compare. */
    public final long revision;
    private final Map<String, Object> values;

    public AppSettings(long revision, Map<String, Object> values) {
        this.revision = revision;
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    /** The master switch. Off by default: AI never runs until the user turns it on. */
    public boolean aiEnabled() {
        return bool(AI_ENABLED, false);
    }

    /** The user's choice for one model. Kept while AI is paused. */
    public boolean modelEnabled(String modelId) {
        return bool(modelEnabledKey(modelId), false);
    }

    /** Whether the model may actually be invoked: chosen, and AI not paused. */
    public boolean modelActive(String modelId) {
        return aiEnabled() && modelEnabled(modelId);
    }

    public boolean haptics() {
        return bool(HAPTICS, true);
    }

    public boolean keySound() {
        return bool(KEY_SOUND, false);
    }

    public boolean wordSuggestions() {
        return bool(WORD_SUGGESTIONS, true);
    }

    public boolean unmeteredOnly() {
        return bool(UNMETERED_ONLY, true);
    }

    public float heightScale() {
        switch (string(KEYBOARD_HEIGHT, HEIGHT_STANDARD)) {
            case HEIGHT_COMPACT:
                return 0.88f;
            case HEIGHT_TALL:
                return 1.12f;
            default:
                return 1f;
        }
    }

    public boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    public String string(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    @SuppressWarnings("unchecked") // Only string sets are ever stored under set keys.
    public Set<String> stringSet(String key) {
        Object value = values.get(key);
        return value instanceof Set ? Collections.unmodifiableSet((Set<String>) value) : Set.of();
    }
}

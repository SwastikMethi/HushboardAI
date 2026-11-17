// The immutable settings snapshot: defaults, typed reads, and independence from its source.
package com.example.aikeyboard.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AppSettingsTest {

    @Test
    public void defaultsAreSafe_aiStartsOffAndNothingIsEnabled() {
        AppSettings settings = new AppSettings(0, Map.of());
        assertFalse(settings.aiEnabled());
        assertFalse(settings.modelEnabled("emo"));
        assertTrue(settings.haptics());
        assertFalse(settings.keySound());
        assertTrue(settings.wordSuggestions());
        assertTrue(settings.unmeteredOnly());
        assertEquals(1.0f, settings.heightScale(), 0.001f);
    }

    @Test
    public void storedValuesOverrideDefaults() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(AppSettings.AI_ENABLED, true);
        stored.put(AppSettings.modelEnabledKey("emo"), true);
        stored.put(AppSettings.KEYBOARD_HEIGHT, "tall");
        stored.put(AppSettings.HAPTICS, false);
        AppSettings settings = new AppSettings(7, stored);

        assertEquals(7, settings.revision);
        assertTrue(settings.aiEnabled());
        assertTrue(settings.modelEnabled("emo"));
        assertFalse(settings.modelEnabled("redact"));
        assertFalse(settings.haptics());
        assertTrue(settings.heightScale() > 1.0f);
    }

    @Test
    public void pausingAiKeepsEachModelChoice() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(AppSettings.AI_ENABLED, false);
        stored.put(AppSettings.modelEnabledKey("redact"), true);
        AppSettings settings = new AppSettings(1, stored);

        assertTrue("the choice is remembered", settings.modelEnabled("redact"));
        assertFalse("but nothing runs while paused", settings.modelActive("redact"));
    }

    @Test
    public void modelSettingsReadWithTheirOwnDefaults() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(AppSettings.modelSettingKey("emo", "limit"), 5);
        AppSettings settings = new AppSettings(1, stored);

        assertEquals(5, settings.integer(AppSettings.modelSettingKey("emo", "limit"), 3));
        assertEquals("default", settings.string(
                AppSettings.modelSettingKey("emo", "skin_tone"), "default"));
        assertEquals(Set.of(), settings.stringSet(AppSettings.BLOCKED_APPS));
    }

    @Test
    public void aSnapshotDoesNotChangeWhenItsSourceDoes() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(AppSettings.AI_ENABLED, true);
        AppSettings settings = new AppSettings(1, stored);
        stored.put(AppSettings.AI_ENABLED, false);
        assertTrue(settings.aiEnabled());
    }
}

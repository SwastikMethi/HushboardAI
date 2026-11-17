// Lets the preference screens read and write the app's one settings store instead of their
// default SharedPreferences, so the keyboard sees every change.
package com.example.aikeyboard.ui.settings;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;

import com.example.aikeyboard.data.SettingsRepository;

public final class SettingsStore extends PreferenceDataStore {

    private final SettingsRepository settings;

    public SettingsStore(SettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return settings.latest().bool(key, defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        // Model switches go through the one method that also clears a crash note.
        if (key.startsWith("model.") && key.endsWith(".enabled")) {
            settings.setModelEnabled(
                    key.substring("model.".length(), key.length() - ".enabled".length()), value);
        } else {
            settings.setBoolean(key, value);
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        return settings.latest().integer(key, defValue);
    }

    @Override
    public void putInt(String key, int value) {
        settings.setInt(key, value);
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        return settings.latest().string(key, defValue);
    }

    @Override
    public void putString(String key, @Nullable String value) {
        if (value != null) settings.setString(key, value);
    }
}

// Per-app AI switches. Lists only apps the keyboard has actually been used in (by package
// name), so no inventory of installed apps is ever requested.
package com.example.aikeyboard.ui.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public final class AppPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String root) {
        SettingsRepository settings = AppContainer.from(requireContext()).settings;
        AppSettings current = settings.latest();
        Context context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        Preference intro = new Preference(context);
        intro.setSummary(R.string.apps_intro);
        intro.setSelectable(false);
        intro.setPersistent(false);
        intro.setIconSpaceReserved(false);
        screen.addPreference(intro);

        Set<String> observed = new TreeSet<>(current.stringSet(AppSettings.OBSERVED_APPS));
        if (observed.isEmpty()) {
            Preference none = new Preference(context);
            none.setSummary(R.string.apps_none);
            none.setSelectable(false);
            none.setPersistent(false);
            none.setIconSpaceReserved(false);
            screen.addPreference(none);
        }
        for (String appPackage : observed) {
            SwitchPreferenceCompat row = new SwitchPreferenceCompat(context);
            // The switch is a view of the blocked set, not a stored value of its own.
            row.setPersistent(false);
            row.setTitle(appPackage);
            row.setIconSpaceReserved(false);
            row.setChecked(!current.stringSet(AppSettings.BLOCKED_APPS).contains(appPackage));
            row.setOnPreferenceChangeListener((preference, allowed) -> {
                Set<String> blocked =
                        new HashSet<>(settings.latest().stringSet(AppSettings.BLOCKED_APPS));
                if ((Boolean) allowed) blocked.remove(appPackage);
                else blocked.add(appPackage);
                settings.setStringSet(AppSettings.BLOCKED_APPS, blocked);
                return true;
            });
            screen.addPreference(row);
        }
        setPreferenceScreen(screen);
    }
}

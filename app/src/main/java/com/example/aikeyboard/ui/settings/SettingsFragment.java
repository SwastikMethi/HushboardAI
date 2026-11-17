// Settings tab: keyboard behaviour, download policy, storage, privacy and the model credit.
// Rows write to the shared settings store, so the keyboard follows each change at once.
package com.example.aikeyboard.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRegistry;

public final class SettingsFragment extends CardPreferenceFragment {

    private static final String VENDOR_URL = "https://desertant.com";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String root) {
        AppContainer app = AppContainer.from(requireContext());
        // Must be set before the rows are created, or they would read SharedPreferences.
        getPreferenceManager().setPreferenceDataStore(new SettingsStore(app.settings));
        setPreferencesFromResource(R.xml.settings, root);

        MainActivity activity = (MainActivity) requireActivity();
        find("storage").setOnPreferenceClickListener(p -> {
            activity.selectTab(R.id.tab_models);
            return true;
        });
        find("apps").setOnPreferenceClickListener(p -> {
            activity.openAppPreferences();
            return true;
        });
        find("privacy").setOnPreferenceClickListener(p -> {
            activity.openPrivacy();
            return true;
        });
        find("attribution").setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(VENDOR_URL)));
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        Context appContext = requireContext().getApplicationContext();
        AppContainer app = AppContainer.from(appContext);
        app.io.execute(() -> {
            long bytes = 0;
            for (ModelDescriptor model : ModelRegistry.all()) {
                bytes += app.models.bytesOnDisk(model.id);
            }
            String used = appContext.getString(R.string.settings_storage_summary,
                    Formatter.formatShortFileSize(appContext, bytes));
            View view = getView();
            if (view != null) view.post(() -> find("storage").setSummary(used));
        });
    }

    private Preference find(String key) {
        return findPreference(key);
    }
}

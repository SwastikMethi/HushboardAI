// One model's detail screen, built from its descriptor: the enable switch, exactly the
// settings the model defines, and its storage. The same class serves every model.
package com.example.aikeyboard.ui.models;

import android.content.Context;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelSettingDefinition;
import com.example.aikeyboard.ui.settings.SettingsStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public final class ModelDetailFragment extends PreferenceFragmentCompat {

    private static final String ARG_MODEL = "model";

    private ModelDescriptor model;
    private ModelRepository models;
    private SwitchPreferenceCompat enable;
    private Preference storage;
    private TryItPreference tryIt;
    // Each setting's row with the action that puts it back to its default.
    private final List<Runnable> resets = new ArrayList<>();

    public static ModelDetailFragment forModel(String modelId) {
        ModelDetailFragment fragment = new ModelDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MODEL, modelId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String root) {
        AppContainer app = AppContainer.from(requireContext());
        models = app.models;
        model = ModelRegistry.find(requireArguments().getString(ARG_MODEL));
        getPreferenceManager().setPreferenceDataStore(new SettingsStore(app.settings));
        Context context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        Preference header = plain(context);
        header.setTitle(model.name);
        header.setSummary(model.summary);
        header.setSelectable(false);
        screen.addPreference(header);

        enable = new SwitchPreferenceCompat(context);
        enable.setKey(AppSettings.modelEnabledKey(model.id));
        enable.setTitle(getString(R.string.detail_enable, model.name));
        enable.setDefaultValue(false);
        enable.setIconSpaceReserved(false);
        screen.addPreference(enable);

        if (!model.settings.isEmpty()) {
            PreferenceCategory category = category(context, R.string.detail_settings);
            screen.addPreference(category);
            for (ModelSettingDefinition definition : model.settings) {
                category.addPreference(settingRow(context, definition));
            }
            Preference reset = plain(context);
            reset.setTitle(R.string.detail_reset);
            reset.setOnPreferenceClickListener(p -> {
                for (Runnable action : resets) action.run(); // this model only
                return true;
            });
            category.addPreference(reset);
        }

        if (ModelTryIt.supports(model.id)) {
            PreferenceCategory tryCategory = category(context, R.string.try_title);
            screen.addPreference(tryCategory);
            tryIt = new TryItPreference(context, (text, onResult) ->
                    app.inference.execute(() -> {
                        String shown;
                        try {
                            shown = ModelTryIt.run(app, context, model.id, text);
                        } catch (Exception e) {
                            // The real failure, in words: never a made-up success.
                            String reason = e.getMessage() != null ? e.getMessage()
                                    : e.getClass().getSimpleName();
                            shown = context.getString(R.string.try_failed, reason);
                        }
                        String result = shown;
                        View view = getView();
                        if (view != null) view.post(() -> onResult.accept(result));
                    }));
            tryCategory.addPreference(tryIt);
        } else if (model.group == ModelDescriptor.Group.VOICE) {
            // Voice models are tried on real audio, in the screen built for that.
            PreferenceCategory tryCategory = category(context, R.string.try_title);
            screen.addPreference(tryCategory);
            Preference open = plain(context);
            open.setKey("try_it"); // lets the screen scroll straight to this row
            open.setTitle(R.string.open_voice_tools);
            open.setOnPreferenceClickListener(p -> {
                ((MainActivity) requireActivity()).openVoiceTools();
                return true;
            });
            tryCategory.addPreference(open);
        }

        PreferenceCategory storageCategory = category(context, R.string.detail_storage);
        screen.addPreference(storageCategory);
        storage = plain(context);
        storage.setTitle(R.string.detail_on_device);
        storage.setSelectable(false);
        storageCategory.addPreference(storage);
        if (model.bundled) {
            storage.setSummary(R.string.detail_bundled);
        } else {
            Preference delete = plain(context);
            delete.setTitle(R.string.detail_delete);
            delete.setOnPreferenceClickListener(p -> {
                confirmDelete();
                return true;
            });
            storageCategory.addPreference(delete);
        }
        setPreferenceScreen(screen);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        models.statuses().observe(getViewLifecycleOwner(), statuses -> {
            // A model can only be switched on once its files are really there.
            boolean ready = models.isReady(model.id);
            enable.setEnabled(ready);
            enable.setSummary(ready ? null : getString(R.string.detail_enable_needs_download));
            if (tryIt != null) tryIt.setReady(ready);
            if (!model.bundled) showStorage();
        });
    }

    private void showStorage() {
        Context appContext = requireContext().getApplicationContext();
        AppContainer.from(appContext).io.execute(() -> {
            String size = Formatter.formatShortFileSize(appContext,
                    models.bytesOnDisk(model.id));
            View view = getView();
            if (view != null) view.post(() -> storage.setSummary(size));
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.detail_delete_confirm, model.name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    models.delete(model.id);
                    getParentFragmentManager().popBackStack();
                })
                .show();
    }

    private Preference settingRow(Context context, ModelSettingDefinition definition) {
        String key = AppSettings.modelSettingKey(model.id, definition.name);
        switch (definition.type) {
            case CHOICE: {
                ListPreference row = new ListPreference(context);
                row.setKey(key);
                row.setEntries(definition.labels);
                row.setEntryValues(definition.values);
                row.setDefaultValue(definition.defaultValue);
                row.setDialogTitle(definition.title);
                row.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                resets.add(() -> row.setValue(definition.defaultValue));
                return titled(row, definition);
            }
            case RANGE: {
                SeekBarPreference row = new SeekBarPreference(context);
                row.setKey(key);
                row.setMin(definition.min);
                row.setMax(definition.max);
                row.setSeekBarIncrement(definition.step);
                row.setShowSeekBarValue(true);
                row.setDefaultValue(definition.defaultNumber);
                if (definition.summary != 0) row.setSummary(definition.summary);
                resets.add(() -> row.setValue(definition.defaultNumber));
                return titled(row, definition);
            }
            default: {
                SwitchPreferenceCompat row = new SwitchPreferenceCompat(context);
                row.setKey(key);
                row.setDefaultValue(definition.defaultOn);
                resets.add(() -> row.setChecked(definition.defaultOn));
                return titled(row, definition);
            }
        }
    }

    private static Preference titled(Preference row, ModelSettingDefinition definition) {
        row.setTitle(definition.title);
        row.setIconSpaceReserved(false);
        return row;
    }

    private static Preference plain(Context context) {
        Preference preference = new Preference(context);
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
        return preference;
    }

    private static PreferenceCategory category(Context context, int title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        category.setIconSpaceReserved(false);
        return category;
    }
}

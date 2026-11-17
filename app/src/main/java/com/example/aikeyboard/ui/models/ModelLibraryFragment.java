// Models tab: the master AI switch and every model, grouped by what it works on. Each row
// shows the model's real asset state and offers the single action that applies to it; the
// user's enable choice is shown separately from whether the files are installed.
package com.example.aikeyboard.ui.models;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.databinding.FragmentModelLibraryBinding;
import com.example.aikeyboard.databinding.ItemGroupHeaderBinding;
import com.example.aikeyboard.databinding.ItemModelBinding;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelDescriptor.Group;
import com.example.aikeyboard.models.ModelDownloader.Failure;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.models.ModelRepository.ModelStatus;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public final class ModelLibraryFragment extends Fragment {

    private FragmentModelLibraryBinding views;
    private final Map<String, ItemModelBinding> rows = new HashMap<>();
    private SettingsRepository settings;
    private ModelRepository models;
    private Disposable settingsSubscription;
    private AppSettings current;
    private Map<String, ModelStatus> statuses = Map.of();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        AppContainer app = AppContainer.from(requireContext());
        settings = app.settings;
        models = app.models;
        current = settings.latest();
        views = FragmentModelLibraryBinding.inflate(inflater, container, false);

        // Click, not checked-change: drawing the saved state must never write it back.
        views.aiMasterSwitch.setOnClickListener(v ->
                settings.setBoolean(AppSettings.AI_ENABLED, views.aiMasterSwitch.isChecked()));

        addGroup(inflater, Group.TEXT, R.string.group_text, 0);
        addGroup(inflater, Group.VOICE, R.string.group_voice, R.string.group_voice_caption);
        addGroup(inflater, Group.IMAGES, R.string.group_images, R.string.group_images_caption);
        addGroup(inflater, Group.DRAWING, R.string.group_drawing, 0);
        return views.getRoot();
    }

    private void addGroup(LayoutInflater inflater, Group group, int title, int caption) {
        ItemGroupHeaderBinding header =
                ItemGroupHeaderBinding.inflate(inflater, views.modelGroups, true);
        header.groupTitle.setText(title);
        if (caption != 0) {
            header.groupCaption.setText(caption);
            header.groupCaption.setVisibility(View.VISIBLE);
        }
        int open = toolsLabel(group);
        if (open != 0) {
            header.groupOpen.setText(open);
            header.groupOpen.setVisibility(View.VISIBLE);
            header.groupOpen.setOnClickListener(
                    v -> ((MainActivity) requireActivity()).openToolsFor(group));
        }
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        views.modelGroups.addView(card);
        for (ModelDescriptor model : ModelRegistry.inGroup(group)) {
            ItemModelBinding row = ItemModelBinding.inflate(inflater, card, true);
            row.getRoot().setTag("model_row_" + model.id);
            row.modelIcon.setImageResource(ModelIcons.of(model.id));
            row.modelChevron.setVisibility(model.available() ? View.VISIBLE : View.GONE);
            row.modelName.setText(model.name);
            row.modelSummary.setText(model.summary);
            row.modelBeta.setVisibility(model.beta ? View.VISIBLE : View.GONE);
            row.modelSwitch.setContentDescription(getString(R.string.enable_model, model.name));
            // setModelEnabled, not a bare write: switching a model back on also clears the
            // note that it was switched off after stopping the app.
            row.modelSwitch.setOnClickListener(v -> settings.setModelEnabled(
                    model.id, row.modelSwitch.isChecked()));
            if (model.available()) {
                row.getRoot().setOnClickListener(v ->
                        ((MainActivity) requireActivity()).openModelDetail(model.id));
            } else {
                row.getRoot().setClickable(false);
            }
            rows.put(model.id, row);
        }
    }

    /** The button that opens a group's own screen, or 0 for models used while typing. */
    static int toolsLabel(Group group) {
        if (group == Group.VOICE) return R.string.open_voice_tools;
        if (group == Group.IMAGES) return R.string.open_image_tools;
        if (group == Group.DRAWING) return R.string.open_drawing;
        return 0;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        models.statuses().observe(getViewLifecycleOwner(), latest -> {
            statuses = latest;
            render();
        });
        // Disposed with the view; the stream arrives on a background thread.
        settingsSubscription = settings.observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(latest -> {
                    current = latest;
                    render();
                });
    }

    @Override
    public void onDestroyView() {
        settingsSubscription.dispose();
        rows.clear();
        views = null;
        super.onDestroyView();
    }

    private void render() {
        if (views == null) return;
        views.aiMasterSwitch.setChecked(current.aiEnabled());
        for (ModelDescriptor model : ModelRegistry.all()) {
            ModelStatus status = statuses.get(model.id);
            if (status != null) render(rows.get(model.id), model, status);
        }
    }

    private void render(ItemModelBinding row, ModelDescriptor model, ModelStatus status) {
        boolean showSwitch = false;
        boolean showProgress = false;
        int action = 0;
        int actionDescription = 0;
        String text;
        switch (status.state) {
            case INCLUDED:
                showSwitch = true;
                text = getString(paused(model) ? R.string.status_paused
                        : R.string.status_included);
                break;
            case READY:
                showSwitch = true;
                // A model the app switched off after it took the app down says so here, so
                // the off switch is never a mystery.
                text = getString(current.bool(AppSettings.modelCrashedKey(model.id), false)
                        ? R.string.status_stopped
                        : paused(model) ? R.string.status_paused : R.string.status_ready);
                break;
            case NOT_INSTALLED:
                action = R.string.action_download;
                actionDescription = R.string.download_model;
                text = getString(R.string.status_size, size(status.bytesTotal));
                break;
            case QUEUED:
                showProgress = true;
                action = R.string.action_cancel;
                actionDescription = R.string.cancel_model_download;
                text = getString(status.failure == Failure.NETWORK
                        ? R.string.status_waiting_network : R.string.status_waiting);
                break;
            case DOWNLOADING:
                showProgress = true;
                action = R.string.action_cancel;
                actionDescription = R.string.cancel_model_download;
                text = getString(R.string.status_downloading, size(status.bytesDone),
                        size(status.bytesTotal));
                break;
            case FAILED:
                action = R.string.action_retry;
                actionDescription = R.string.retry_model_download;
                text = getString(failureText(status.failure));
                break;
            case INCOMPATIBLE:
                text = getString(models.tooBigForDevice(model.id)
                        ? R.string.status_needs_memory : R.string.status_incompatible);
                break;
            default:
                text = getString(R.string.status_coming_later);
                break;
        }
        row.modelStatus.setText(text);
        row.modelSwitch.setVisibility(showSwitch ? View.VISIBLE : View.GONE);
        row.modelSwitch.setChecked(current.modelEnabled(model.id));
        renderProgress(row, showProgress, status);
        renderAction(row, model, status.state, action, actionDescription);
    }

    // A chosen model that is not running because the master switch is off.
    private boolean paused(ModelDescriptor model) {
        return !current.aiEnabled() && current.modelEnabled(model.id);
    }

    private void renderProgress(ItemModelBinding row, boolean show, ModelStatus status) {
        boolean indeterminate = status.state == AssetState.QUEUED || status.bytesTotal <= 0;
        if (row.modelProgress.isIndeterminate() != indeterminate) {
            // The indicator may only change mode while it is hidden.
            row.modelProgress.setVisibility(View.GONE);
            row.modelProgress.setIndeterminate(indeterminate);
        }
        if (!indeterminate) {
            row.modelProgress.setProgress((int) (100 * status.bytesDone / status.bytesTotal));
        }
        row.modelProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void renderAction(ItemModelBinding row, ModelDescriptor model, AssetState state,
            int label, int description) {
        if (label == 0) {
            row.modelAction.setVisibility(View.GONE);
            return;
        }
        row.modelAction.setText(label);
        row.modelAction.setContentDescription(getString(description, model.name));
        row.modelAction.setVisibility(View.VISIBLE);
        boolean inProgress = state == AssetState.QUEUED || state == AssetState.DOWNLOADING;
        row.modelAction.setOnClickListener(v -> {
            if (inProgress) models.cancel(model.id);
            else models.download(model.id);
        });
    }

    private static int failureText(Failure failure) {
        if (failure == Failure.STORAGE) return R.string.status_failed_storage;
        if (failure == Failure.CORRUPT) return R.string.status_failed_corrupt;
        if (failure == Failure.SERVER) return R.string.status_failed_server;
        return R.string.status_failed_network;
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(requireContext(), bytes);
    }
}

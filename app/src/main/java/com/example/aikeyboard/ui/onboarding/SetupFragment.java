// Onboarding: explains what the keyboard can see, walks the user through turning it on and
// choosing it in Android's own screens, and offers a field to try it in. The app cannot enable
// itself; it only reports what the system says each time the screen comes back.
package com.example.aikeyboard.ui.onboarding;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.databinding.FragmentSetupBinding;
import com.example.aikeyboard.ime.AiInputMethodService;

import java.util.ArrayList;
import java.util.List;

public final class SetupFragment extends Fragment {

    public enum Step { ENABLE, SELECT, DONE }

    private FragmentSetupBinding views;
    // The system keyboard picker is a dialog: closing it returns focus without a resume.
    private final ViewTreeObserver.OnWindowFocusChangeListener onFocus = hasFocus -> {
        if (hasFocus) showCurrentStep();
    };

    /** Where the user is, from the enabled keyboards and the current one as Android reports. */
    public static Step step(List<String> enabledImeIds, String currentImeId, String ourId) {
        if (!enabledImeIds.contains(ourId)) return Step.ENABLE;
        return ourId.equals(currentImeId) ? Step.DONE : Step.SELECT;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        views = FragmentSetupBinding.inflate(inflater, container, false);
        views.setupEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        views.setupChoose.setOnClickListener(v -> inputMethodManager().showInputMethodPicker());
        views.setupModels.setOnClickListener(
                v -> ((MainActivity) requireActivity()).selectTab(R.id.tab_models));
        views.getRoot().getViewTreeObserver().addOnWindowFocusChangeListener(onFocus);
        return views.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        showCurrentStep();
    }

    @Override
    public void onDestroyView() {
        views.getRoot().getViewTreeObserver().removeOnWindowFocusChangeListener(onFocus);
        views = null;
        super.onDestroyView();
    }

    private InputMethodManager inputMethodManager() {
        return requireContext().getSystemService(InputMethodManager.class);
    }

    private void showCurrentStep() {
        if (views == null) return;
        String ourId = new ComponentName(requireContext(), AiInputMethodService.class)
                .flattenToShortString();
        List<String> enabled = new ArrayList<>();
        for (InputMethodInfo info : inputMethodManager().getEnabledInputMethodList()) {
            enabled.add(info.getId());
        }
        String current = Settings.Secure.getString(requireContext().getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);

        Step step = step(enabled, current, ourId);
        views.setupStatus.setText(step == Step.ENABLE ? R.string.setup_status_enable
                : step == Step.SELECT ? R.string.setup_status_select
                : R.string.setup_status_done);
        // Choosing only makes sense once the keyboard is enabled.
        views.setupChoose.setEnabled(step != Step.ENABLE);
    }
}

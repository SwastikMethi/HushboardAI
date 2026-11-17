// The system keyboard service: Android lifecycle, the keyboard view and the connection to the
// focused field. Behaviour lives in KeyboardController; text access in EditorBridge.
package com.example.aikeyboard.ime;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.view.ContextThemeWrapper;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.databinding.KeyboardViewBinding;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class AiInputMethodService extends InputMethodService {

    private static final String WORD_LIST = "dictionaries/en_common.txt";

    private KeyboardController controller;
    // Wired to the saved settings in M2.
    private boolean hapticsEnabled = true;
    private boolean soundEnabled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        EditorBridge editor = new EditorBridge(this::getCurrentInputConnection);
        controller = new KeyboardController(this, editor, loadWordList());
    }

    private LocalWordSuggester loadWordList() {
        try (InputStream in = getAssets().open(WORD_LIST)) {
            return LocalWordSuggester.load(in);
        } catch (IOException e) {
            // Typing must work without suggestions; the strip simply stays empty.
            return new LocalWordSuggester(List.of());
        }
    }

    @Override
    public View onCreateInputView() {
        // A service has no Material theme of its own; Material widgets need one to inflate.
        Context themed = new ContextThemeWrapper(this, R.style.Theme_AiKeyboard);
        KeyboardViewBinding binding = KeyboardViewBinding.inflate(LayoutInflater.from(themed));

        // From targetSdk 35 the IME window is edge-to-edge, so the keyboard pads itself clear
        // of the navigation area instead of relying on the system to do it.
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            // (InputMethodService has its own inner Insets class, so the type is not named.)
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            view.setPadding(0, 0, 0, bottom);
            return insets;
        });
        controller.attach(binding);
        return binding.getRoot();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        controller.onStartInput(info, restarting);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        controller.onSelectionChanged();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        // Stop any held-key repeat before the view goes away.
        controller.onFinishInput();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // Never the full-screen "extract" editor: the user keeps seeing the app they type in.
        return false;
    }

    void showKeyboardPicker() {
        getSystemService(InputMethodManager.class).showInputMethodPicker();
    }

    void openCompanionApp() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void keyFeedback(View view) {
        if (hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (soundEnabled) {
            getSystemService(AudioManager.class)
                    .playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD);
        }
    }
}

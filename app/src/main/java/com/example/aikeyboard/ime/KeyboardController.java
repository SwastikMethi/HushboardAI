// Keyboard behaviour: turns key presses into edits and keeps layer, shift and suggestions in
// step with the field. Owned by the service, so its state survives the view being re-created
// (rotation, theme change); the view is attached again each time.
package com.example.aikeyboard.ime;

import android.os.SystemClock;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.databinding.KeyboardViewBinding;
import com.example.aikeyboard.ime.KeyboardLayout.Key;
import com.example.aikeyboard.ime.KeyboardLayout.Layer;
import com.example.aikeyboard.ime.view.KeyGridView;
import com.example.aikeyboard.ime.view.SuggestionStripView;

import java.util.List;
import java.util.Locale;

public final class KeyboardController
        implements KeyGridView.Listener, SuggestionStripView.Listener {

    private static final int WORD_LOOKBEHIND = 48;
    private static final int MAX_SUGGESTIONS = 3;

    private final AiInputMethodService service;
    private final EditorBridge editor;
    private final LocalWordSuggester suggester;

    private AppSettings settings;
    private KeyboardViewBinding views;
    private InputSession session;
    private ShiftState shift = new ShiftState();
    private Layer layer = Layer.LETTERS;
    private boolean emojiShown;
    private String currentWord = "";
    private List<String> suggestions = List.of();

    KeyboardController(AiInputMethodService service, EditorBridge editor,
            LocalWordSuggester suggester, AppSettings settings) {
        this.service = service;
        this.editor = editor;
        this.suggester = suggester;
        this.settings = settings;
    }

    /** Saved preferences changed, here or in the companion app. */
    void onSettings(AppSettings latest) {
        settings = latest;
        if (session != null) readEditor();
        render();
    }

    /** Called for every new keyboard view; the controller's state is drawn onto it. */
    void attach(KeyboardViewBinding binding) {
        views = binding;
        views.keys.setListener(this);
        views.keys.setSpaceLabel(service.getString(R.string.subtype_english));
        views.suggestions.setListener(this);
        views.toolSettings.setOnClickListener(
                v -> service.openCompanionApp(MainActivity.TAB_SETTINGS));
        views.emojiPicker.setOnEmojiPickedListener(item -> editor.commitText(item.getEmoji()));
        views.emojiLetters.setOnClickListener(v -> {
            emojiShown = false;
            render();
        });
        views.emojiBackspace.setOnClickListener(v -> editor.backspace());
        render();
    }

    /** A field gained the keyboard. A restart of the same field keeps the user's layer. */
    void onStartInput(EditorInfo info, boolean restarting) {
        session = new InputSession(info);
        if (!restarting) {
            layer = Layer.LETTERS;
            emojiShown = false;
            shift = new ShiftState();
        }
        readEditor();
        render();
    }

    /** The text or cursor changed, by us or by the host app. */
    void onSelectionChanged() {
        if (session == null) return;
        session.bumpGeneration();
        readEditor();
        render();
    }

    void onFinishInput() {
        if (views != null) views.keys.cancelTouches();
    }

    private void readEditor() {
        shift.onAutoCaps(editor.wantsCapital(session.inputType));
        currentWord = session.wordSuggestions && settings.wordSuggestions()
                ? trailingWord(editor.textBeforeCursor(WORD_LOOKBEHIND)) : "";
        suggestions = currentWord.isEmpty()
                ? List.of() : suggester.suggest(currentWord, MAX_SUGGESTIONS);
    }

    // The run of letters that ends at the cursor: the word being typed.
    private static String trailingWord(String before) {
        int start = before.length();
        while (start > 0 && Character.isLetter(before.charAt(start - 1))) start--;
        return before.substring(start);
    }

    private void render() {
        if (views == null) return;
        views.keys.setRows(KeyboardLayout.rows(layer));
        views.keys.setHeightScale(settings.heightScale());
        views.keys.setShiftMode(shift.mode());
        views.suggestions.setSuggestions(suggestions);
        // INVISIBLE, not GONE: the key grid keeps giving the emoji panel its height.
        views.keys.setVisibility(emojiShown ? View.INVISIBLE : View.VISIBLE);
        views.emojiPanel.setVisibility(emojiShown ? View.VISIBLE : View.GONE);
        if (session != null) renderEnterKey(session.imeOptions);
    }

    private void renderEnterKey(int imeOptions) {
        int description;
        String glyph = "→";
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            description = R.string.key_new_line;
            glyph = "↵";
        } else {
            switch (imeOptions & EditorInfo.IME_MASK_ACTION) {
                case EditorInfo.IME_ACTION_GO:
                    description = R.string.key_go;
                    break;
                case EditorInfo.IME_ACTION_SEARCH:
                    description = R.string.key_search;
                    break;
                case EditorInfo.IME_ACTION_SEND:
                    description = R.string.key_send;
                    break;
                case EditorInfo.IME_ACTION_NEXT:
                    description = R.string.key_next;
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    description = R.string.key_done;
                    glyph = "✓";
                    break;
                case EditorInfo.IME_ACTION_PREVIOUS:
                    description = R.string.key_previous;
                    glyph = "←";
                    break;
                default:
                    description = R.string.key_enter;
                    glyph = "↵";
                    break;
            }
        }
        views.keys.setEnter(glyph, service.getString(description));
    }

    @Override
    public void onPress(Key key) {
        service.keyFeedback(views.keys);
    }

    @Override
    public void onKey(Key key) {
        if (session == null) return;
        switch (key.code) {
            case KeyboardLayout.CODE_TEXT:
                editor.commitText(shift.isUpperCase()
                        ? key.label.toUpperCase(Locale.ROOT) : key.label);
                shift.onCharacterTyped();
                break;
            case KeyboardLayout.CODE_SPACE:
                editor.commitText(" ");
                shift.onCharacterTyped();
                break;
            case KeyboardLayout.CODE_BACKSPACE:
                editor.backspace();
                break;
            case KeyboardLayout.CODE_SHIFT:
                shift.onShiftTapped(SystemClock.uptimeMillis());
                break;
            case KeyboardLayout.CODE_SYMBOLS:
                layer = Layer.SYMBOLS;
                break;
            case KeyboardLayout.CODE_MORE_SYMBOLS:
                layer = Layer.MORE_SYMBOLS;
                break;
            case KeyboardLayout.CODE_LETTERS:
                layer = Layer.LETTERS;
                break;
            case KeyboardLayout.CODE_ENTER:
                editor.enter(session.imeOptions);
                break;
            case KeyboardLayout.CODE_EMOJI:
                emojiShown = true;
                break;
            default:
                break;
        }
        render();
    }

    @Override
    public void onLongPress(Key key) {
        if (key.code == KeyboardLayout.CODE_SPACE) service.showKeyboardPicker();
    }

    @Override
    public void onSuggestionPicked(String word) {
        int cursor = editor.cursorPosition();
        if (cursor < 0 || currentWord.isEmpty()) return;
        // replaceRange re-checks the text, so a suggestion for an older word cannot be applied.
        editor.replaceRange(cursor - currentWord.length(), cursor, currentWord, word + " ");
    }
}

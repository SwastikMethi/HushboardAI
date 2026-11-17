// Keyboard behaviour: turns key presses into edits and keeps layer, shift, suggestions and
// the AI controls in step with the field and the saved settings. Owned by the service, so
// its state survives the view being re-created (rotation); the view is attached again each
// time. Runs entirely on the main thread; model work is handed to the AI coordinator.
package com.example.aikeyboard.ime;

import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.core.content.ContextCompat;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.ai.AiCoordinator;
import com.example.aikeyboard.ai.DraftSnapshot;
import com.example.aikeyboard.ai.InferenceScheduler;
import com.example.aikeyboard.core.InputEligibility;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.databinding.ItemAiModelBinding;
import com.example.aikeyboard.databinding.KeyboardViewBinding;
import com.example.aikeyboard.ime.KeyboardLayout.Key;
import com.example.aikeyboard.ime.KeyboardLayout.Layer;
import com.example.aikeyboard.ime.view.KeyGridView;
import com.example.aikeyboard.ime.view.SuggestionStripView;
import com.example.aikeyboard.ime.view.SuggestionStripView.Kind;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class KeyboardController implements KeyGridView.Listener,
        SuggestionStripView.Listener, AiCoordinator.Listener {

    private static final int WORD_LOOKBEHIND = 48;
    private static final int MAX_SUGGESTIONS = 3;
    // How much of a draft the keyboard reads for AI: a bounded window around the cursor.
    private static final int DRAFT_BEFORE = 1000;
    private static final int DRAFT_AFTER = 200;
    // Models that act while typing, in the order the AI panel lists them.
    private static final String[] PANEL_MODELS = {"emo"};

    private final AiInputMethodService service;
    private final EditorBridge editor;
    private final LocalWordSuggester suggester;
    private final AppContainer app;
    private final AiCoordinator coordinator;
    private final Map<String, ItemAiModelBinding> panelRows = new HashMap<>();

    private AppSettings settings;
    private KeyboardViewBinding views;
    private InputSession session;
    private ShiftState shift = new ShiftState();
    private Layer layer = Layer.LETTERS;
    private boolean emojiShown;
    private boolean aiPanelShown;
    private String currentWord = "";
    private List<String> suggestions = List.of();
    private List<String> emoji = List.of();
    private boolean setupNeeded;
    private Verdict verdict = Verdict.AI_OFF;

    KeyboardController(AiInputMethodService service, EditorBridge editor,
            LocalWordSuggester suggester, AppContainer app) {
        this.service = service;
        this.editor = editor;
        this.suggester = suggester;
        this.app = app;
        this.settings = app.settings.latest();
        coordinator = new AiCoordinator(new InferenceScheduler(app.timer, app.inference),
                ContextCompat.getMainExecutor(service), app.emo, app.models::isReady, this);
        coordinator.onSettings(settings);
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

        views.aiToggle.setOnClickListener(v -> showAiPanel(true));
        views.aiPanel.aiPanelBack.setOnClickListener(v -> showAiPanel(false));
        views.aiPanel.aiPanelClose.setOnClickListener(v -> showAiPanel(false));
        views.aiPanel.aiPanelManage.setOnClickListener(
                v -> service.openCompanionApp(MainActivity.TAB_MODELS));
        // Click, not checked-change: drawing the saved state must never write it back.
        views.aiPanel.aiPanelMaster.setOnClickListener(v -> app.settings.setBoolean(
                AppSettings.AI_ENABLED, views.aiPanel.aiPanelMaster.isChecked()));
        buildPanelRows();
        render();
    }

    private void buildPanelRows() {
        panelRows.clear();
        views.aiPanel.aiPanelModels.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(views.getRoot().getContext());
        for (String modelId : PANEL_MODELS) {
            ModelDescriptor model = ModelRegistry.find(modelId);
            ItemAiModelBinding row =
                    ItemAiModelBinding.inflate(inflater, views.aiPanel.aiPanelModels, true);
            row.aiModelName.setText(model.name);
            row.aiModelSummary.setText(model.summary);
            row.aiModelSwitch.setContentDescription(
                    service.getString(R.string.enable_model, model.name));
            row.aiModelSwitch.setOnClickListener(v -> app.settings.setModelEnabled(
                    modelId, row.aiModelSwitch.isChecked()));
            panelRows.put(modelId, row);
        }
    }

    private void showAiPanel(boolean show) {
        aiPanelShown = show;
        render();
    }

    /** Saved preferences changed, here or in the companion app. */
    void onSettings(AppSettings latest) {
        // The sweep is for one moment only: the user has just turned AI on.
        boolean justTurnedOn = !settings.aiEnabled() && latest.aiEnabled();
        settings = latest;
        coordinator.onSettings(latest);
        if (session != null) readEditor();
        render();
        if (justTurnedOn && views != null) views.aiToggle.playEnableSweep();
    }

    /** A field gained the keyboard. A restart of the same field keeps the user's layer. */
    void onStartInput(EditorInfo info, boolean restarting) {
        session = new InputSession(info);
        if (!restarting) {
            layer = Layer.LETTERS;
            emojiShown = false;
            aiPanelShown = false;
            shift = new ShiftState();
            rememberApp(session.editorPackage);
        }
        readEditor();
        render();
    }

    // Per-app AI preferences list only apps the keyboard has been used in: package ids,
    // nothing else, and no inventory of installed apps.
    private void rememberApp(String editorPackage) {
        if (editorPackage == null || editorPackage.equals(service.getPackageName())) return;
        Set<String> seen = settings.stringSet(AppSettings.OBSERVED_APPS);
        if (seen.contains(editorPackage)) return;
        Set<String> updated = new HashSet<>(seen);
        updated.add(editorPackage);
        app.settings.setStringSet(AppSettings.OBSERVED_APPS, updated);
    }

    /** The text or cursor changed, by us or by the host app. */
    void onSelectionChanged() {
        if (session == null) return;
        session.bumpGeneration();
        readEditor();
        render();
    }

    void onFinishInput() {
        coordinator.onSessionEnded();
        if (views != null) views.keys.cancelTouches();
    }

    private void readEditor() {
        shift.onAutoCaps(editor.wantsCapital(session.inputType));
        currentWord = session.wordSuggestions && settings.wordSuggestions()
                ? trailingWord(editor.textBeforeCursor(WORD_LOOKBEHIND)) : "";
        suggestions = currentWord.isEmpty()
                ? List.of() : suggester.suggest(currentWord, MAX_SUGGESTIONS);

        verdict = InputEligibility.check(settings, session);
        // In a field where AI may not run, its text is not even read for AI.
        EditorBridge.Draft draft =
                verdict == Verdict.ALLOWED ? editor.readDraft(DRAFT_BEFORE, DRAFT_AFTER) : null;
        coordinator.onDraft(draft == null
                ? new DraftSnapshot(session.id, session.generation(), session.editorPackage,
                        "", 0, 0, -1, true)
                : new DraftSnapshot(session.id, session.generation(), session.editorPackage,
                        draft.text, draft.selectionStart, draft.selectionEnd, draft.offset,
                        draft.partial), verdict);
    }

    // The run of letters that ends at the cursor: the word being typed.
    private static String trailingWord(String before) {
        int start = before.length();
        while (start > 0 && Character.isLetter(before.charAt(start - 1))) start--;
        return before.substring(start);
    }

    @Override
    public void onEmojiSuggestions(List<String> latest) {
        emoji = latest;
        renderStrip();
    }

    @Override
    public void onSetupNeeded(boolean needed) {
        setupNeeded = needed;
        renderStrip();
    }

    private void render() {
        if (views == null) return;
        views.keys.setRows(KeyboardLayout.rows(layer));
        views.keys.setHeightScale(settings.heightScale());
        views.keys.setShiftMode(shift.mode());
        // INVISIBLE, not GONE: the key grid keeps giving the emoji panel its height.
        views.keys.setVisibility(emojiShown ? View.INVISIBLE : View.VISIBLE);
        views.emojiPanel.setVisibility(emojiShown ? View.VISIBLE : View.GONE);
        if (session != null) renderEnterKey(session.imeOptions);
        renderStrip();

        views.aiToggle.setOn(settings.aiEnabled());
        // Likewise INVISIBLE: the typing area keeps giving the AI panel its height.
        views.typingArea.setVisibility(aiPanelShown ? View.INVISIBLE : View.VISIBLE);
        views.aiPanel.getRoot().setVisibility(aiPanelShown ? View.VISIBLE : View.GONE);
        if (aiPanelShown) renderAiPanel();
    }

    // Words win while a word is being typed; emoji fill the pause after it.
    private void renderStrip() {
        if (views == null) return;
        if (!suggestions.isEmpty()) {
            views.suggestions.setItems(Kind.WORDS, suggestions);
        } else if (!emoji.isEmpty()) {
            views.suggestions.setItems(Kind.EMOJI, emoji);
        } else if (setupNeeded) {
            views.suggestions.setItems(Kind.SETUP,
                    List.of(service.getString(R.string.strip_setup_models)));
        } else {
            views.suggestions.setItems(Kind.WORDS, List.of());
        }
    }

    private void renderAiPanel() {
        views.aiPanel.aiPanelMaster.setChecked(settings.aiEnabled());
        views.aiPanel.aiPanelStatus.setText(panelStatus());
        for (String modelId : PANEL_MODELS) {
            ItemAiModelBinding row = panelRows.get(modelId);
            boolean ready = app.models.isReady(modelId);
            boolean crashed = settings.bool(AppSettings.modelCrashedKey(modelId), false);
            row.aiModelSwitch.setVisibility(ready ? View.VISIBLE : View.GONE);
            row.aiModelSwitch.setChecked(settings.modelEnabled(modelId));
            row.aiModelStatus.setVisibility(!ready || crashed ? View.VISIBLE : View.GONE);
            row.aiModelStatus.setText(ready ? R.string.ai_model_crashed
                    : R.string.ai_model_not_downloaded);
        }
    }

    // Says why AI is unavailable in this field, without ever showing the field's contents.
    private int panelStatus() {
        switch (verdict) {
            case PASSWORD_FIELD:
                return R.string.ai_panel_password;
            case NO_LEARNING_FIELD:
                return R.string.ai_panel_private;
            case UNSUPPORTED_FIELD:
                return R.string.ai_panel_unsupported;
            case APP_BLOCKED:
                return R.string.ai_panel_blocked;
            case AI_OFF:
                return R.string.ai_panel_paused;
            default:
                return R.string.ai_panel_hint;
        }
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
    public void onStripItemPicked(Kind kind, String text) {
        if (session == null) return;
        switch (kind) {
            case EMOJI:
                // Shown emoji always belong to the current draft: the coordinator clears
                // them on every change and drops late results. A tap is the user's consent.
                editor.commitText(text);
                break;
            case SETUP:
                service.openCompanionApp(MainActivity.TAB_MODELS);
                break;
            default: {
                int cursor = editor.cursorPosition();
                if (cursor < 0 || currentWord.isEmpty()) return;
                // replaceRange re-checks the text, so a suggestion for an older word
                // cannot be applied.
                editor.replaceRange(cursor - currentWord.length(), cursor, currentWord,
                        text + " ");
                break;
            }
        }
    }
}

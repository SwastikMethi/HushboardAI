// Keyboard behaviour: turns key presses into edits and keeps layer, shift, suggestions,
// notices and the AI controls in step with the field and the saved settings. Owned by the
// service, so its state survives the view being re-created (rotation); the view is attached
// again each time. Runs entirely on the main thread; model work is handed to the coordinator.
package com.example.aikeyboard.ime;

import android.Manifest;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.text.InputType;
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
import com.example.aikeyboard.ai.NoticeText;
import com.example.aikeyboard.ai.RedactionPlan;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.ai.Suggestion.Notice;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.ai.Suggestion.TopicTag;
import com.example.aikeyboard.ai.SuggestionPolicy;
import com.example.aikeyboard.core.InputEligibility;
import com.example.aikeyboard.core.InputEligibility.Verdict;
import com.example.aikeyboard.core.TextEdit;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.databinding.ItemAiModelBinding;
import com.example.aikeyboard.databinding.KeyboardViewBinding;
import com.example.aikeyboard.ime.KeyboardLayout.Key;
import com.example.aikeyboard.ime.KeyboardLayout.Layer;
import com.example.aikeyboard.ime.view.KeyGridView;
import com.example.aikeyboard.ime.view.SuggestionStripView;
import com.example.aikeyboard.ime.view.SuggestionStripView.Kind;
import com.example.aikeyboard.media.ShareResultController;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.ui.models.ModelIcons;
import com.example.aikeyboard.ui.tools.ImageToolsFragment;
import com.example.aikeyboard.ui.tools.ShapeRecognizer;

import java.io.IOException;
import java.util.Arrays;
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
    private static final String[] PANEL_MODELS = {"emo", "redact", "toxic", "tongue", "gist"};

    // Dictation is for a sentence or two; longer recordings belong in the Voice tools screen.
    private static final int DICTATION_MAX_SECONDS = 60;

    /** What the strip is showing right now, which decides what its slots do. */
    private enum Strip { EMPTY, WORDS, UNDO, NOTICE, EMOJI, MESSAGE, SETUP, LISTENING, TRANSCRIBING }

    private enum Dictation { OFF, LISTENING, TRANSCRIBING }

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
    private Verdict verdict = Verdict.AI_OFF;
    private DraftSnapshot lastSnapshot;
    // How much text is selected, as the system last reported it (no text is read to know).
    private int selectionLength;

    // What the coordinator currently offers for the draft.
    private List<String> emoji = List.of();
    private Notice notice;
    private boolean setupNeeded;
    private Strip strip = Strip.EMPTY;

    // Session-local only: gone when the field loses the keyboard, never stored.
    private Notice reviewing;
    private String reviewMessage;
    // Topics the user asked for, with the draft they were computed from. Non-null while the
    // topics panel is open.
    private List<TopicTag> topics;
    private DraftSnapshot topicsFor;
    private LanguageHint language;
    private boolean redactSkipped;
    private List<TextEdit> undo = List.of();
    private String stripMessage;

    private Dictation dictation = Dictation.OFF;
    // Counts dictations, so a transcript that arrives after its dictation was cancelled, or
    // after the field was left, is recognised and dropped.
    private int dictationRun;
    // The transcript being previewed; non-null while its panel is open. Never typed for the
    // user: it goes into the field only when they tap Insert.
    private String dictated;

    // The drawing panel. The stroke itself lives in the panel's view; this is what is said
    // about it.
    private final ShapeRecognizer recognizer;
    private boolean drawShown;
    private int drawStatus = R.string.drawing_hint;
    // The focused field as the system described it: what it accepts, for inserting a picture.
    private EditorInfo editorInfo;

    // The picture panel. The chosen picture and what was found in it are held by this
    // object, in memory only, until the panel is closed or another field takes the keyboard.
    // It is the Image tools screen's own worker, used here as a plain object.
    private final ImageToolsFragment.Session pictures;
    private boolean pictureShown;
    // What the panel's heading says instead of its title: the warning a flagged picture
    // gets before it is sent, or that it could not be sent. 0 for neither.
    private int pictureNotice;
    // Which field a held picture belongs to, or null when none is held. A picture is chosen
    // in the system's picker, which covers the app, so it arrives while no field has the
    // keyboard: it belongs to the field the picker was opened from, and is shown whenever
    // that field has the keyboard again. Android often starts a field's input twice in a
    // row on the way back, which is why this is tied to the field and not to one start.
    private String pictureFor;
    // The field the picker was opened from, until its picture arrives; and the last real
    // field to have the keyboard, which stands in when a picture arrives unasked.
    private String pickerOpenedFrom;
    private String lastField;

    KeyboardController(AiInputMethodService service, EditorBridge editor,
            LocalWordSuggester suggester, AppContainer app) {
        this.service = service;
        this.editor = editor;
        this.suggester = suggester;
        this.app = app;
        this.settings = app.settings.latest();
        recognizer = new ShapeRecognizer(service);
        pictures = new ImageToolsFragment.Session(
                (Application) service.getApplicationContext());
        // Lives as long as the keyboard service, which is as long as this object.
        pictures.state().observeForever(state -> {
            if (views != null && pictureShown) renderPicturePanel();
        });
        coordinator = new AiCoordinator(new InferenceScheduler(app.timer, app.inference),
                ContextCompat.getMainExecutor(service), app.emo, app.redact, app.toxic,
                app.tongue, app.gist, app.models::isReady, this);
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
        views.toolMic.setOnClickListener(v -> startDictation());
        views.toolDraw.setOnClickListener(v -> showDrawPanel(true));
        views.toolImage.setOnClickListener(v -> {
            if (!canPickPicture()) return;
            pickerOpenedFrom = lastField;
            service.pickPicture();
        });
        views.picturePanel.pictureBack.setOnClickListener(v -> {
            closePicture();
            render();
        });
        views.picturePanel.pictureSend.setOnClickListener(v -> sendPicture());
        views.drawPanel.drawBack.setOnClickListener(v -> showDrawPanel(false));
        views.drawPanel.drawClear.setOnClickListener(v -> clearDrawing());
        views.drawPanel.drawInsert.setOnClickListener(v -> insertDrawing());
        views.drawPanel.drawSurface.setListener(this::onDrawn);
        // A new view starts with an empty surface, whatever was on the old one.
        drawStatus = R.string.drawing_hint;
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

        views.noticePanel.noticeBack.setOnClickListener(v -> closeReview());
        views.noticePanel.noticeDismiss.setOnClickListener(v -> {
            if (reviewing != null) coordinator.dismissNotice();
            closeReview();
        });
        views.aiPanel.aiPanelTag.setOnClickListener(v -> coordinator.requestTopics());
        views.noticePanel.noticePrimary.setOnClickListener(v -> onReviewAction());
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
            row.aiModelIcon.setImageResource(ModelIcons.of(modelId));
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
        // Each opening starts from the top: the first models, not where it was last left.
        if (show && views != null) views.aiPanel.aiPanelScroll.scrollTo(0, 0);
        render();
    }

    /** Saved preferences changed, here or in the companion app. */
    void onSettings(AppSettings latest) {
        // The sweep is for one moment only: the user has just turned AI on.
        boolean justTurnedOn = !settings.aiEnabled() && latest.aiEnabled();
        settings = latest;
        coordinator.onSettings(latest);
        if (session != null) readEditor();
        // Dictation switched off (or AI paused) mid-recording: the microphone closes now.
        if (dictation != Dictation.OFF && !canDictate()) cancelDictation();
        if (drawShown && !canDraw()) closeDrawing();
        // While a picture is on its way back no field has the keyboard: only the models count.
        if (pictureFor != null && !(pictureShown ? canPickPicture() : imageModelUsable())) {
            closePicture();
        }
        render();
        if (justTurnedOn && views != null) views.aiToggle.playEnableSweep();
    }

    /**
     * Some field has taken input; whether the keyboard will be shown for it is not known
     * yet. Until it is, there is no session: input can go on with the keyboard hidden (a
     * hardware keyboard, a password manager filling a field) and the system still reports
     * every change, but without a session nothing is read, judged or passed to a model, and
     * the last field's permissions are never applied to this one.
     */
    void onInputStarted() {
        session = null;
        editorInfo = null;
    }

    /** A field gained the keyboard. A restart of the same field keeps the user's layer. */
    void onStartInput(EditorInfo info, boolean restarting) {
        session = new InputSession(info);
        editorInfo = info;
        selectionLength = info.initialSelStart < 0 || info.initialSelEnd < 0 ? 0
                : Math.abs(info.initialSelEnd - info.initialSelStart);
        if (!restarting) {
            layer = Layer.LETTERS;
            emojiShown = false;
            aiPanelShown = false;
            shift = new ShiftState();
            forgetSessionState();
            rememberApp(session.editorPackage);
        }
        readEditor();
        // A window with nothing to type into can have the keyboard for a moment while
        // screens change. It is not a field, and decides nothing about a picture.
        if (info.inputType != InputType.TYPE_NULL) {
            lastField = fieldKey(info);
            if (pictureFor != null) {
                // A held picture stays with its own field and never follows into another.
                if (pictureFor.equals(lastField) && canPickPicture()) pictureShown = true;
                else closePicture();
            }
        }
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
    void onSelectionChanged(int selectionStart, int selectionEnd) {
        if (session == null) return;
        selectionLength = Math.abs(selectionEnd - selectionStart);
        session.bumpGeneration();
        // Whatever was being reviewed was for the text as it was; it may not be applied now.
        reviewing = null;
        topics = null;
        readEditor();
        render();
    }

    void onFinishInput() {
        coordinator.onSessionEnded();
        forgetSessionState();
        if (views != null) views.keys.cancelTouches();
        // No field has the keyboard now: see onInputStarted.
        session = null;
        editorInfo = null;
    }

    /** The keyboard left the screen: a recording must not carry on behind it. */
    void onHidden() {
        cancelDictation();
        render();
    }

    // Drafts, proposals, the undo record and any dictation live only as long as the field
    // has the keyboard.
    private void forgetSessionState() {
        cancelDictation();
        closeDrawing();
        reviewing = null;
        topics = null;
        reviewMessage = null;
        undo = List.of();
        stripMessage = null;
        lastSnapshot = null;
    }

    private void readEditor() {
        shift.onAutoCaps(editor.wantsCapital(session.inputType));
        currentWord = session.wordSuggestions && settings.wordSuggestions()
                ? trailingWord(editor.textBeforeCursor(WORD_LOOKBEHIND)) : "";
        suggestions = currentWord.isEmpty()
                ? List.of() : suggester.suggest(currentWord, MAX_SUGGESTIONS);

        verdict = InputEligibility.check(settings, session);
        // In a field where AI may not run, its text is not even read for AI. Nor is a
        // selection bigger than the window: the editor would hand over all of it (select-all
        // in a long note), and the keyboard only ever looks at a bounded piece of a draft.
        boolean readable = verdict == Verdict.ALLOWED
                && selectionLength <= DRAFT_BEFORE + DRAFT_AFTER;
        EditorBridge.Draft draft = readable ? editor.readDraft(DRAFT_BEFORE, DRAFT_AFTER) : null;
        lastSnapshot = draft == null
                ? new DraftSnapshot(session.id, session.generation(), session.editorPackage,
                        "", 0, 0, -1, true)
                : new DraftSnapshot(session.id, session.generation(), session.editorPackage,
                        draft.text, draft.selectionStart, draft.selectionEnd, draft.offset,
                        draft.partial);
        coordinator.onDraft(lastSnapshot, verdict);
    }

    // The run of letters that ends at the cursor: the word being typed.
    private static String trailingWord(String before) {
        int start = before.length();
        while (start > 0 && Character.isLetter(before.charAt(start - 1))) start--;
        return before.substring(start);
    }

    @Override
    public void onSuggestions(List<String> latestEmoji, Notice latestNotice) {
        emoji = latestEmoji;
        notice = latestNotice;
        renderStrip();
    }

    @Override
    public void onSetupNeeded(boolean needed) {
        setupNeeded = needed;
        renderStrip();
    }

    @Override
    public void onLanguage(LanguageHint hint, boolean skipped) {
        language = hint;
        redactSkipped = skipped;
        if (views != null && aiPanelShown) renderAiPanel();
    }

    @Override
    public void onTopics(DraftSnapshot draft, List<TopicTag> found) {
        topics = found;
        topicsFor = draft;
        reviewMessage = null;
        aiPanelShown = false;
        render();
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
        // INVISIBLE, not GONE: it keeps the AI control centred.
        views.toolMic.setVisibility(canDictate() ? View.VISIBLE : View.INVISIBLE);
        views.toolDraw.setVisibility(canDraw() ? View.VISIBLE : View.INVISIBLE);
        views.toolImage.setVisibility(canPickPicture() ? View.VISIBLE : View.INVISIBLE);
        boolean reviewShown = reviewing != null || topics != null || dictated != null;
        boolean overlay = aiPanelShown || reviewShown || drawShown || pictureShown;
        // Likewise INVISIBLE: the typing area keeps giving the panels their height.
        views.typingArea.setVisibility(overlay ? View.INVISIBLE : View.VISIBLE);
        views.aiPanel.getRoot().setVisibility(
                aiPanelShown && !reviewShown ? View.VISIBLE : View.GONE);
        views.noticePanel.getRoot().setVisibility(reviewShown ? View.VISIBLE : View.GONE);
        views.drawPanel.getRoot().setVisibility(
                drawShown && !reviewShown && !aiPanelShown ? View.VISIBLE : View.GONE);
        if (aiPanelShown) renderAiPanel();
        if (reviewShown) renderReview();
        views.picturePanel.getRoot().setVisibility(
                pictureShown && !reviewShown && !aiPanelShown && !drawShown
                        ? View.VISIBLE : View.GONE);
        if (drawShown) renderDrawPanel();
        if (pictureShown) renderPicturePanel();
    }

    // An undo offer or a notice outranks everything: they only appear once typing has
    // paused, and a notice hidden behind word completions would be easy to miss. Then word
    // completions, then emoji.
    private void renderStrip() {
        if (views == null) return;
        // A dictation the user started comes first: its Stop and Cancel must stay in reach.
        if (dictation == Dictation.LISTENING) {
            show(Strip.LISTENING, Kind.STATUS, Arrays.asList(
                    service.getString(R.string.dictation_listening),
                    service.getString(R.string.voice_stop),
                    service.getString(R.string.action_cancel)));
        } else if (dictation == Dictation.TRANSCRIBING) {
            show(Strip.TRANSCRIBING, Kind.STATUS, Arrays.asList(
                    service.getString(R.string.dictation_working),
                    service.getString(R.string.action_cancel)));
        } else if (!undo.isEmpty()) {
            show(Strip.UNDO, Kind.STATUS, Arrays.asList(
                    service.getResources().getQuantityString(R.plurals.masked_items,
                            undo.size(), undo.size()),
                    service.getString(R.string.action_undo),
                    service.getString(R.string.action_dismiss)));
        } else if (stripMessage != null) {
            show(Strip.MESSAGE, Kind.NOTICE, List.of(stripMessage));
        } else if (notice != null) {
            show(Strip.NOTICE, Kind.NOTICE, Arrays.asList(
                    service.getString(notice.kind == Notice.Kind.PRIVACY
                            ? R.string.notice_privacy : R.string.notice_tone),
                    service.getString(R.string.action_review),
                    service.getString(R.string.action_dismiss)));
        } else if (!suggestions.isEmpty()) {
            show(Strip.WORDS, Kind.WORDS, suggestions);
        } else if (!emoji.isEmpty()) {
            show(Strip.EMOJI, Kind.EMOJI, emoji);
        } else if (setupNeeded) {
            show(Strip.SETUP, Kind.SETUP,
                    List.of(service.getString(R.string.strip_setup_models)));
        } else {
            show(Strip.EMPTY, Kind.WORDS, List.of());
        }
    }

    private void show(Strip what, Kind kind, List<String> items) {
        strip = what;
        views.suggestions.setItems(kind, items);
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
            String status = !ready ? service.getString(R.string.ai_model_not_downloaded)
                    : crashed ? service.getString(R.string.ai_model_crashed)
                    : languageStatus(modelId);
            row.aiModelStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
            row.aiModelStatus.setText(status);
        }
        // Topics are computed only on this tap, and only where AI may run at all.
        boolean canTag = verdict == Verdict.ALLOWED && settings.modelActive("gist")
                && app.models.isReady("gist")
                && lastSnapshot != null && !lastSnapshot.text.trim().isEmpty();
        views.aiPanel.aiPanelTag.setVisibility(canTag ? View.VISIBLE : View.GONE);
    }

    // What the language hint means for a row, or null. The hint is a guess, shown as one.
    private String languageStatus(String modelId) {
        if (language == null || !settings.modelActive(modelId)) return null;
        String name = new Locale(language.language).getDisplayLanguage();
        if ("tongue".equals(modelId)) {
            return language.reliable ? service.getString(R.string.ai_language_looks, name)
                    : service.getString(R.string.ai_language_unsure);
        }
        if ("redact".equals(modelId) && redactSkipped) {
            return service.getString(R.string.ai_redact_uncovered, name);
        }
        return null;
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

    // ---- Reviewing a notice ----

    private void openReview() {
        reviewing = notice;
        reviewMessage = null;
        render();
    }

    private void closeReview() {
        reviewing = null;
        topics = null;
        dictated = null;
        reviewMessage = null;
        render();
    }

    // ---- Dictation ----

    // The microphone is offered only where AI may run and transcription really can.
    private boolean canDictate() {
        return verdict == Verdict.ALLOWED && settings.modelActive("voz")
                && app.models.isReady("voz");
    }

    private void startDictation() {
        if (session == null || dictation != Dictation.OFF || !canDictate()) return;
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // A keyboard cannot show the system's prompt; a small screen of the app does.
            service.askForMicrophone();
            return;
        }
        int run = ++dictationRun;
        boolean started = app.capture.start(DICTATION_MAX_SECONDS, () -> {
            if (run == dictationRun) finishDictation();
        });
        if (started) dictation = Dictation.LISTENING;
        else stripMessage = service.getString(R.string.dictation_mic_unavailable);
        render();
    }

    // The user tapped Stop, or the time limit was reached.
    private void finishDictation() {
        if (dictation != Dictation.LISTENING) return;
        AudioClip clip = app.capture.stop();
        if (clip == null || clip.seconds() < 0.3) {
            dictation = Dictation.OFF;
            stripMessage = service.getString(R.string.dictation_nothing);
            render();
            return;
        }
        dictation = Dictation.TRANSCRIBING;
        int run = dictationRun;
        AppSettings current = settings;
        // The one inference thread; typing stays live on the main thread meanwhile.
        app.inference.execute(() -> {
            String text;
            try {
                text = app.voz.run(clip, current).text;
            } catch (Exception e) {
                text = null;
            }
            String heard = text;
            ContextCompat.getMainExecutor(service).execute(() -> onDictated(run, heard));
        });
        render();
    }

    private void onDictated(int run, String text) {
        // Cancelled, or the field was left, while the model was working: the text is dropped.
        if (run != dictationRun || dictation != Dictation.TRANSCRIBING) return;
        dictation = Dictation.OFF;
        if (text == null) stripMessage = service.getString(R.string.dictation_failed);
        else if (text.isEmpty()) stripMessage = service.getString(R.string.dictation_nothing);
        else dictated = text;
        render();
    }

    // Closes the microphone and makes any transcript still on its way unwanted. A model call
    // that has already started cannot be interrupted; its result is simply never shown.
    private void cancelDictation() {
        if (dictation == Dictation.LISTENING) app.capture.cancel();
        dictation = Dictation.OFF;
        dictationRun++;
        dictated = null;
    }

    // ---- Pictures ----

    // Offered only where AI may run and an image model really can.
    private boolean canPickPicture() {
        return verdict == Verdict.ALLOWED && imageModelUsable();
    }

    private boolean imageModelUsable() {
        return ImageToolsFragment.usable(app, settings, "eye")
                || ImageToolsFragment.usable(app, settings, "moderator");
    }

    /** The picker screen's result: the one picture the user chose, already read. */
    void onPicturePicked(Bitmap picture) {
        String field = pickerOpenedFrom != null ? pickerOpenedFrom : lastField;
        pickerOpenedFrom = null;
        // No field to show it in, or the image models went away meanwhile: not kept.
        if (field == null || !imageModelUsable()) return;
        pictureFor = field;
        pictureNotice = 0;
        pictures.open(picture);
        // Usually the picker is still covering the app, and the picture is shown when its
        // field has the keyboard again (onStartInput). If the keyboard is up now, show it.
        if (service.isInputViewShown()) {
            if (field.equals(lastField) && canPickPicture()) pictureShown = true;
            else closePicture();
            render();
        }
    }

    // Leaves the panel and lets go of the picture; a result still on its way is dropped.
    private void closePicture() {
        pictureShown = false;
        pictureFor = null;
        pictureNotice = 0;
        pictures.forget();
    }

    // Sends the picture the way a drawing is sent. Moderator warns and never blocks: the
    // first tap on a flagged picture only says so, and the second is the user's decision.
    private void sendPicture() {
        ImageToolsFragment.State state = pictures.state().getValue();
        if (session == null || !pictureShown || !state.analysed()) return;
        if (state.flagged() && pictureNotice != R.string.picture_warning) {
            pictureNotice = R.string.picture_warning;
            renderPicturePanel();
            return;
        }
        send(state.picture, ShareResultController.JPEG,
                service.getString(R.string.image_preview), () -> {
                    closePicture();
                    render();
                }, () -> {
                    pictureNotice = R.string.image_share_failed;
                    if (views != null && pictureShown) renderPicturePanel();
                });
    }

    private static String fieldKey(EditorInfo info) {
        return info.packageName + "/" + info.fieldId;
    }

    private void renderPicturePanel() {
        ImageToolsFragment.State state = pictures.state().getValue();
        views.picturePanel.pictureStatus.setText(state.working ? R.string.image_working
                : pictureNotice != 0 ? pictureNotice : R.string.picture_title);
        // The button says what will happen here: not every field takes pictures.
        boolean takesPictures = EditorBridge.accepts(editorInfo, ShareResultController.JPEG);
        boolean warned = pictureNotice == R.string.picture_warning;
        views.picturePanel.pictureSend.setText(takesPictures
                ? (warned ? R.string.picture_insert_anyway : R.string.draw_insert)
                : (warned ? R.string.image_share_anyway : R.string.draw_share));
        views.picturePanel.pictureSend.setEnabled(state.analysed());
        views.picturePanel.pictureSend.setAlpha(state.analysed() ? 1f : 0.4f);
        views.picturePanel.picturePreview.setImageBitmap(state.picture);
        // INVISIBLE, not GONE: the picture keeps its share of the width while it is looked at.
        views.picturePanel.pictureResult.setVisibility(
                state.analysed() ? View.VISIBLE : View.INVISIBLE);
        if (state.analysed()) {
            ImageToolsFragment.showResult(state, views.picturePanel.pictureKind,
                    views.picturePanel.pictureTags, views.picturePanel.pictureDetails,
                    views.picturePanel.pictureSensitivity, views.picturePanel.pictureNotes,
                    R.string.picture_note);
        }
    }

    // ---- Drawing ----

    // Offered only where AI may run and the shape model really can.
    private boolean canDraw() {
        return verdict == Verdict.ALLOWED && recognizer.usable();
    }

    private void showDrawPanel(boolean show) {
        if (show && !canDraw()) return;
        if (show) drawShown = true;
        else closeDrawing();
        render();
    }

    // Leaves the panel and throws the drawing away; a shape still being recognised is
    // dropped when it arrives.
    private void closeDrawing() {
        drawShown = false;
        clearDrawing();
    }

    private void clearDrawing() {
        recognizer.forget();
        drawStatus = R.string.drawing_hint;
        if (views != null) {
            views.drawPanel.drawSurface.clear();
            if (drawShown) renderDrawPanel();
        }
    }

    private void onDrawn(List<float[]> stroke) {
        drawStatus = R.string.drawing_working;
        renderDrawPanel();
        recognizer.recognise(stroke, (shape, failed) -> {
            if (views == null || !drawShown) return;
            views.drawPanel.drawSurface.setShape(shape);
            drawStatus = failed ? R.string.drawing_failed
                    : shape == null ? R.string.drawing_unclear
                    : ShapeRecognizer.nameOf(shape.kind);
            renderDrawPanel();
        });
    }

    private void renderDrawPanel() {
        views.drawPanel.drawStatus.setText(drawStatus);
        boolean drawn = !views.drawPanel.drawSurface.isEmpty();
        boolean ready = drawn && drawStatus != R.string.drawing_working;
        views.drawPanel.drawClear.setEnabled(drawn);
        views.drawPanel.drawInsert.setEnabled(ready);
        views.drawPanel.drawInsert.setAlpha(ready ? 1f : 0.4f);
        // The button says what will happen here: not every field takes pictures.
        views.drawPanel.drawInsert.setText(
                EditorBridge.accepts(editorInfo, ShareResultController.PNG)
                        ? R.string.draw_insert : R.string.draw_share);
    }

    private void insertDrawing() {
        if (session == null || views == null) return;
        send(views.drawPanel.drawSurface.render(), ShareResultController.PNG,
                service.getString(R.string.shape_other), () -> {
                    closeDrawing();
                    render();
                }, () -> {
                    drawStatus = R.string.image_share_failed;
                    if (views != null && drawShown) renderDrawPanel();
                });
    }

    // How a drawing or a chosen picture leaves the keyboard. The tap is the user's consent.
    // The picture goes into the field if the field takes pictures; if it does not, or the
    // host app turns it down, the share sheet is offered.
    private void send(Bitmap picture, String mime, String label, Runnable sent,
            Runnable failed) {
        InputSession sentFor = session;
        EditorInfo field = editorInfo;
        app.io.execute(() -> {
            Uri uri;
            try {
                uri = ShareResultController.writePicture(service, picture, mime);
            } catch (IOException e) {
                uri = null;
            }
            Uri written = uri;
            ContextCompat.getMainExecutor(service).execute(() -> {
                // The field changed while the picture was being written: nothing is sent.
                if (session != sentFor) return;
                if (written == null) {
                    failed.run();
                    return;
                }
                if (!editor.commitContent(field, written, mime, label)) {
                    service.share(ShareResultController.shareIntent(written, mime));
                }
                sent.run();
            });
        });
    }

    private void renderTranscript() {
        views.noticePanel.noticeTitle.setText(R.string.dictation_title);
        views.noticePanel.noticeDetail.setText(dictated);
        views.noticePanel.noticeNote.setText(R.string.dictation_note);
        views.noticePanel.noticeMessage.setVisibility(View.GONE);
        views.noticePanel.noticePrimary.setVisibility(View.VISIBLE);
        views.noticePanel.noticePrimary.setText(R.string.dictation_insert);
    }

    private boolean maskingOffered() {
        return ModelRegistry.REDACT_MODE_MASK.equals(settings.string(
                AppSettings.modelSettingKey("redact", ModelRegistry.REDACT_MODE),
                ModelRegistry.REDACT_MODE_WARN));
    }

    private void renderTopics() {
        StringBuilder detail = new StringBuilder();
        for (TopicTag topic : topics) {
            if (detail.length() > 0) detail.append('\n');
            detail.append(topic.name);
        }
        String note = service.getString(R.string.topics_note);
        if (topicsFor.partial) {
            note = service.getString(R.string.review_scope_partial) + " " + note;
        }
        views.noticePanel.noticeTitle.setText(R.string.topics_title);
        views.noticePanel.noticeDetail.setText(
                topics.isEmpty() ? service.getString(R.string.topics_none) : detail);
        views.noticePanel.noticeNote.setText(note);
        views.noticePanel.noticeMessage.setText(reviewMessage);
        views.noticePanel.noticeMessage.setVisibility(
                reviewMessage == null ? View.GONE : View.VISIBLE);
        views.noticePanel.noticePrimary.setVisibility(
                topics.isEmpty() ? View.GONE : View.VISIBLE);
        views.noticePanel.noticePrimary.setText(R.string.topics_insert);
    }

    private void renderReview() {
        if (dictated != null) {
            renderTranscript();
            return;
        }
        if (reviewing == null) {
            renderTopics();
            return;
        }
        boolean privacy = reviewing.kind == Notice.Kind.PRIVACY;
        StringBuilder detail = new StringBuilder();
        if (privacy) {
            for (SensitiveSpan span : reviewing.spans) {
                if (detail.length() > 0) detail.append('\n');
                detail.append(service.getString(R.string.review_line,
                        service.getString(NoticeText.categoryName(span.category)),
                        span.original, span.placeholder));
            }
        } else {
            for (String tone : reviewing.tone) {
                if (detail.length() > 0) detail.append('\n');
                detail.append(service.getString(NoticeText.toneName(tone)));
            }
        }
        // When the editor shared only part of the draft, say so: never imply a full check.
        String note = service.getString(privacy ? R.string.review_privacy_note
                : R.string.review_tone_note);
        if (reviewing.partial) {
            note = service.getString(R.string.review_scope_partial) + " " + note;
        }
        views.noticePanel.noticeTitle.setText(privacy ? R.string.notice_privacy
                : R.string.notice_tone);
        views.noticePanel.noticeDetail.setText(detail);
        views.noticePanel.noticeNote.setText(note);
        views.noticePanel.noticeMessage.setText(reviewMessage);
        views.noticePanel.noticeMessage.setVisibility(
                reviewMessage == null ? View.GONE : View.VISIBLE);

        // Masking is offered only if the user chose it. Without a trustworthy position in the
        // editor the app offers a copy of the masked text instead of editing anything.
        boolean action = privacy && maskingOffered();
        views.noticePanel.noticePrimary.setVisibility(action ? View.VISIBLE : View.GONE);
        if (action) {
            views.noticePanel.noticePrimary.setText(reviewing.draft.offset >= 0
                    ? R.string.review_mask : R.string.review_copy);
        }
    }

    private void onReviewAction() {
        if (session == null) return;
        if (dictated != null) {
            // The tap is the user's consent; the text goes in at the cursor as it is now.
            editor.commitText(dictated);
            dictated = null;
            render();
            return;
        }
        if (reviewing == null) {
            if (topics == null) return;
            // Tags go in at the cursor, and only if the draft is still the one that was tagged.
            if (topicsFor.sameMoment(lastSnapshot)) {
                editor.commitText(SuggestionPolicy.hashtags(topics));
                topics = null;
            } else {
                reviewMessage = service.getString(R.string.review_changed);
            }
            render();
            return;
        }
        if (reviewing.draft.offset < 0) {
            ClipboardManager clipboard = service.getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    service.getString(R.string.review_copy),
                    RedactionPlan.maskedText(reviewing.draft.text, reviewing.spans)));
            reviewMessage = service.getString(R.string.review_copied);
            render();
            return;
        }
        List<TextEdit> edits = RedactionPlan.mask(reviewing.spans, reviewing.draft.offset);
        // The bridge re-reads the field and replaces only if every span is still there.
        if (reviewing.draft.sameMoment(lastSnapshot) && editor.replaceRanges(edits)) {
            undo = RedactionPlan.undo(edits);
            reviewing = null;
            reviewMessage = null;
        } else {
            reviewMessage = service.getString(R.string.review_changed);
        }
        render();
    }

    private void undoMask() {
        // Guarded the same way: the placeholders must still be exactly where they were put.
        boolean restored = editor.replaceRanges(undo);
        undo = List.of();
        stripMessage = restored ? null : service.getString(R.string.undo_failed);
        render();
    }

    // ---- Keys ----

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
        // A key was pressed, so the keyboard is on screen and some field has input. If the
        // system never said which (callbacks can arrive in orders this code has not seen),
        // ask it now: typing must work, and the field asked about is the current one, so
        // its own rules apply.
        if (session == null) {
            EditorInfo current = service.getCurrentInputEditorInfo();
            if (current == null) return;
            onStartInput(current, true);
        }
        // The user has moved on: the undo offer and any one-off message go away.
        undo = List.of();
        stripMessage = null;
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
                // Enter does exactly what the key says. No notice ever blocks or delays it.
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
    public void onStripItemPicked(Kind kind, int index, String text) {
        if (session == null) return;
        switch (strip) {
            case WORDS: {
                int cursor = editor.cursorPosition();
                if (cursor < 0 || currentWord.isEmpty()) return;
                // replaceRange re-checks the text, so a suggestion for an older word
                // cannot be applied.
                editor.replaceRange(cursor - currentWord.length(), cursor, currentWord,
                        text + " ");
                break;
            }
            case EMOJI:
                // Shown emoji always belong to the current draft: the coordinator clears
                // them on every change and drops late results. A tap is the user's consent.
                editor.commitText(text);
                break;
            case NOTICE:
                if (index == 2) coordinator.dismissNotice();
                else openReview();
                break;
            case UNDO:
                if (index == 1) {
                    undoMask();
                } else if (index == 2) {
                    undo = List.of();
                    render();
                }
                break;
            case MESSAGE:
                stripMessage = null;
                render();
                break;
            case SETUP:
                service.openCompanionApp(MainActivity.TAB_MODELS);
                break;
            case LISTENING:
                if (index == 1) {
                    finishDictation();
                } else if (index == 2) {
                    cancelDictation();
                    render();
                }
                break;
            case TRANSCRIBING:
                if (index == 1) {
                    cancelDictation();
                    render();
                }
                break;
            default:
                break;
        }
    }
}

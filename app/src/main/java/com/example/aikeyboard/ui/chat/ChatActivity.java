// Kids Chat: a demo of a chat for young children, with two pretend users on one phone, each
// with a face and a colour of their own. They can type, with emoji ideas as they go, send a
// picture, or draw on a pad that tidies their shapes. A message
// is checked on the phone before it is sent (see ChatChecks): unkind wording is refused,
// personal details are shown masked first, and a picture the nudity check flags is refused.
// The conversation lives in memory only and is gone when the screen is closed; nothing typed
// or picked here is written to disk or leaves the phone.
package com.example.aikeyboard.ui.chat;

import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.MainActivity;
import com.example.aikeyboard.R;
import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.databinding.ActivityChatBinding;
import com.example.aikeyboard.databinding.ItemChatMessageBinding;
import com.example.aikeyboard.media.ImageImport;
import com.example.aikeyboard.media.Vision.RecognizedShape;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.Seen;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;
import com.example.aikeyboard.ui.tools.ShapeRecognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ChatActivity extends AppCompatActivity {

    // Every one of these must be installed before anything can be sent.
    private static final String[] SAFETY_MODELS = {"toxic", "redact", "moderator"};
    // Extras fetched along with them. Nothing waits for these: emoji ideas, tidy shapes and
    // the look at a photo simply appear once their model is on the phone.
    private static final String[] EXTRA_MODELS = {"emo", "shapes", "eye"};
    // How long after the last key emoji ideas are looked for.
    private static final long IDEAS_PAUSE_MS = 400;
    private static final int[] CRAYONS = {R.color.kids_mia, R.color.kids_leo, R.color.kids_sun,
            R.color.kids_leaf, R.color.kids_ink};
    // A face is this big when it is that child's turn, and this big when it is not.
    private static final int FACE_BIG_DP = 60;
    private static final int FACE_SMALL_DP = 48;

    /** One message in the conversation. */
    static final class Message {
        /** Who sent it: 0 or 1. */
        final int sender;
        /** The words, or null for a picture. */
        final String text;
        /** The picture, or null for words. */
        final Bitmap picture;
        /** For a photo: what the picture model can see in it, in a few words. Else empty. */
        final List<String> seen;

        Message(int sender, String text, Bitmap picture, List<String> seen) {
            this.sender = sender;
            this.text = text;
            this.picture = picture;
            this.seen = seen;
        }
    }

    /** What the screen shows. Replaced as a whole, on the main thread only. */
    static final class State {
        final List<Message> messages;
        /** Who is typing now: 0 or 1. Their messages sit on the right. */
        final int typing;
        final boolean checking;
        /** One line about the last attempt to send, in the app's own words, or 0. */
        final int notice;
        /** While the sender decides about personal details: the message as typed, else null. */
        final String typed;
        /** The same message with each personal detail replaced by its label. */
        final String masked;
        /** True from when a message has gone until the screen has emptied the field. */
        final boolean clearInput;
        /** Emoji that fit what is in the field; empty when there is nothing to suggest. */
        final List<String> ideas;
        /** While the sender decides about a photo that shows a person: that photo. */
        final Bitmap pending;

        State(List<Message> messages, int typing, boolean checking, int notice, String typed,
                String masked, boolean clearInput, List<String> ideas, Bitmap pending) {
            this.messages = messages;
            this.typing = typing;
            this.checking = checking;
            this.notice = notice;
            this.typed = typed;
            this.masked = masked;
            this.clearInput = clearInput;
            this.ideas = ideas;
            this.pending = pending;
        }
    }

    /** The conversation and its checks; outlives rotation and ends when the screen is left. */
    public static final class Session extends AndroidViewModel {

        private final AppContainer app;
        private final Executor main;
        private final AppSettings rules;
        /**
         * The checks every message passes. Not final so a device test can put a harmless
         * picture under rules that refuse it, there being no explicit picture in this
         * project; the app itself never replaces them.
         */
        public ChatChecks checks;
        private final MutableLiveData<State> state = new MutableLiveData<>();
        // Everything below is read and changed on the main thread only.
        private final List<Message> messages = new ArrayList<>();
        private int typing;
        private boolean checking;
        private int notice;
        private String typed;
        private String masked;
        private boolean clearInput;
        private List<String> ideas = List.of();
        private Bitmap pending;
        private List<String> pendingSeen = List.of();
        // What the field held when ideas were last asked for, and the pause before asking.
        private String drafted = "";
        private ScheduledFuture<?> ideasDue;
        // Set when the screen is left for good: a check still running reports to nobody.
        private boolean left;

        public Session(@NonNull Application application) {
            super(application);
            app = AppContainer.from(application);
            main = ContextCompat.getMainExecutor(application);
            rules = ChatChecks.kidRules();
            checks = ChatChecks.with(app, application, rules);
            publish();
        }

        LiveData<State> state() {
            return state;
        }

        void typeAs(int who) {
            if (who == typing || busy()) return;
            typing = who;
            notice = 0;
            publish();
        }

        /** Checks a typed message, and sends it if nothing stands in the way. */
        void send(String text) {
            String message = text.trim();
            if (message.isEmpty() || busy()) return;
            checking = true;
            notice = 0;
            publish();
            // The one inference thread: this and the keyboard's suggestions take turns.
            app.inference.execute(() -> {
                TextVerdict verdict = checks.text(message);
                main.execute(() -> {
                    if (left) return;
                    checking = false;
                    if (verdict.outcome == Outcome.CLEAR) {
                        add(message);
                    } else if (verdict.outcome == Outcome.PERSONAL) {
                        typed = message;
                        masked = verdict.masked;
                    } else {
                        notice = verdict.outcome == Outcome.UNKIND ? R.string.chat_unkind
                                : verdict.outcome == Outcome.OTHER_LANGUAGE
                                        ? R.string.chat_other_language
                                        : R.string.chat_unchecked;
                    }
                    publish();
                });
            });
        }

        /** Reads and checks a chosen picture, and sends it if the check lets it through. */
        void sendPicture(Uri uri) {
            if (busy()) return;
            checking = true;
            notice = 0;
            publish();
            app.io.execute(() -> {
                Bitmap picture;
                try {
                    // ponytail: every picture stays in memory at up to 1600 px a side, the
                    // copy the model looked at. A long chat full of pictures would need
                    // smaller copies for the list.
                    picture = ImageImport.decode(getApplication(), uri, ImageImport.MAX_SIDE);
                } catch (IOException e) {
                    main.execute(
                            () -> pictureChecked(null, null, R.string.chat_picture_unreadable));
                    return;
                }
                check(picture, true);
            });
        }

        /** Checks a drawing from the pad like any picture, and sends it if it passes. */
        void sendDrawing(Bitmap drawing) {
            if (busy()) return;
            checking = true;
            notice = 0;
            publish();
            check(drawing, false);
        }

        // A photo that passes the nudity check is also looked at for people and for a few
        // words to say about it. A drawing from the pad is the child's own and is not.
        private void check(Bitmap picture, boolean photo) {
            app.inference.execute(() -> {
                boolean allowed = checks.pictureAllowed(picture);
                Seen seen = allowed && photo ? checks.look(picture) : null;
                main.execute(() -> pictureChecked(allowed ? picture : null, seen,
                        R.string.chat_picture_refused));
            });
        }

        /** The sender's answer to the card about people in a photo: send it after all. */
        void sendPhotoAnyway() {
            if (pending == null) return;
            messages.add(new Message(typing, null, pending, pendingSeen));
            dropPhoto();
        }

        /** The sender's answer to that card: this photo is not sent. */
        void dropPhoto() {
            pending = null;
            pendingSeen = List.of();
            publish();
        }

        /**
         * Told what is in the field each time it changes. After a short pause the emoji model
         * is asked what fits, if it is on the phone. Ideas stay up while more is typed, so the
         * row does not come and go, and are replaced when the answer for the new words is in.
         */
        void drafted(String text) {
            String draft = text.trim();
            if (draft.equals(drafted)) return;
            drafted = draft;
            if (ideasDue != null) ideasDue.cancel(false);
            if (draft.isEmpty()) {
                if (!ideas.isEmpty()) {
                    ideas = List.of();
                    publish();
                }
                return;
            }
            if (!app.models.isReady("emo")) return;
            ideasDue = app.timer.schedule(() -> app.inference.execute(() -> {
                List<String> found = new ArrayList<>();
                try {
                    for (EmojiCandidate idea : app.emo.run(draft, rules)) found.add(idea.emoji);
                } catch (Exception e) {
                    // No ideas this time. Nothing depends on them.
                }
                main.execute(() -> {
                    // Typed on since, or gone: these ideas were for other words.
                    if (left || !draft.equals(drafted)) return;
                    ideas = found;
                    publish();
                });
            }), IDEAS_PAUSE_MS, TimeUnit.MILLISECONDS);
        }

        // How a picture's check ends: the sender is told why it cannot go, or is asked about
        // the person in it, or it joins the conversation.
        private void pictureChecked(Bitmap allowed, Seen seen, int refusal) {
            if (left) return;
            checking = false;
            if (allowed == null) {
                notice = refusal;
            } else if (seen != null && seen.person) {
                pending = allowed;
                pendingSeen = seen.words;
            } else {
                messages.add(new Message(typing, null, allowed,
                        seen == null ? List.of() : seen.words));
            }
            publish();
        }

        /** The sender's answer to the personal-details card: send the masked message. */
        void sendMasked() {
            if (typed != null) decide(masked);
        }

        /** The sender's answer to the card: send the message as it was typed and checked. */
        void sendAsWritten() {
            if (typed != null) decide(typed);
        }

        /** The sender's answer to the card: send nothing and go back to the message. */
        void changeIt() {
            decide(null);
        }

        void inputCleared() {
            clearInput = false;
            publish();
        }

        private void decide(String toSend) {
            if (toSend != null) add(toSend);
            typed = null;
            masked = null;
            publish();
        }

        private void add(String text) {
            messages.add(new Message(typing, text, null, List.of()));
            clearInput = true;
        }

        private boolean busy() {
            return checking || typed != null || pending != null;
        }

        private void publish() {
            state.setValue(new State(List.copyOf(messages), typing, checking, notice, typed,
                    masked, clearInput, ideas, pending));
        }

        @Override
        protected void onCleared() {
            left = true;
            if (ideasDue != null) ideasDue.cancel(false);
        }
    }

    private ActivityChatBinding views;
    private AppContainer app;
    private Session session;
    private final Messages list = new Messages();
    private TextView[] ideas;
    private ImageButton[] crayons;
    // The drawing pad is the screen's own business: closed again when the screen is rebuilt.
    private boolean padOpen;
    private final OnBackPressedCallback backClosesThePad = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            showPad(false);
        }
    };

    // The system's photo picker: the app is handed the one picture chosen and nothing else,
    // and needs no storage permission.
    private final ActivityResultLauncher<PickVisualMediaRequest> pickPicture =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    this::onPicturePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = AppContainer.from(this);
        session = new ViewModelProvider(this).get(Session.class);
        views = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(views.getRoot());

        // The window is edge-to-edge (targetSdk 35+): keep the screen clear of the system
        // bars and any cutout, and lift it above the keyboard while one is showing.
        ViewCompat.setOnApplyWindowInsetsListener(views.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right,
                    Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });

        LinearLayoutManager newestAtTheBottom = new LinearLayoutManager(this);
        newestAtTheBottom.setStackFromEnd(true);
        views.chatMessages.setLayoutManager(newestAtTheBottom);
        views.chatMessages.setAdapter(list);

        // The field takes no more than the checks were tried at (see ChatChecks).
        views.chatInput.setFilters(
                new InputFilter[] {new InputFilter.LengthFilter(ChatChecks.MESSAGE_LIMIT)});
        views.chatUserOne.setOnClickListener(v -> session.typeAs(0));
        views.chatUserTwo.setOnClickListener(v -> session.typeAs(1));
        views.chatSend.setOnClickListener(
                v -> session.send(views.chatInput.getText().toString()));
        views.chatSendMasked.setOnClickListener(v -> session.sendMasked());
        views.chatChange.setOnClickListener(v -> session.changeIt());
        views.chatSendWritten.setOnClickListener(v -> session.sendAsWritten());
        views.chatPickAnother.setOnClickListener(v -> {
            session.dropPhoto();
            choosePicture();
        });
        views.chatDropPhoto.setOnClickListener(v -> session.dropPhoto());
        views.chatSendPhoto.setOnClickListener(v -> session.sendPhotoAnyway());
        views.chatPeoplePhoto.setClipToOutline(true);
        views.chatDownload.setOnClickListener(v -> downloadMissingModels());
        // Why a download is waiting or failed, and the way to stop or retry it, are on the
        // Models screen; Back returns here.
        views.chatModels.setOnClickListener(v -> startActivity(
                new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MODELS)));
        views.chatPicture.setOnClickListener(v -> choosePicture());

        ideas = new TextView[] {views.chatIdeaOne, views.chatIdeaTwo, views.chatIdeaThree,
                views.chatIdeaFour};
        for (TextView idea : ideas) idea.setOnClickListener(v -> addToMessage(idea.getText()));
        views.chatInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                session.drafted(text.toString());
            }
        });

        crayons = new ImageButton[] {views.chatCrayonOne, views.chatCrayonTwo,
                views.chatCrayonThree, views.chatCrayonFour, views.chatCrayonFive};
        for (int i = 0; i < crayons.length; i++) {
            int crayon = i;
            crayons[i].setOnClickListener(v -> pickCrayon(crayon));
        }
        views.chatDraw.setOnClickListener(v -> showPad(true));
        views.chatPadClose.setOnClickListener(v -> showPad(false));
        views.chatUndo.setOnClickListener(v -> views.chatCanvas.undo());
        views.chatClear.setOnClickListener(v -> views.chatCanvas.clear());
        views.chatSendDrawing.setOnClickListener(v -> sendDrawing());
        views.chatCanvas.setListener(new KidsDrawView.Listener() {
            @Override
            public void onStroke(Object stroke, List<float[]> points) {
                tidy(stroke, points);
            }

            @Override
            public void onChanged() {
                if (views.chatCanvas.isEmpty()) views.chatPadHint.setText(R.string.chat_pad_hint);
                render();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, backClosesThePad);

        session.state().observe(this, state -> render());
        app.models.statuses().observe(this, statuses -> render());
    }

    // An emoji idea goes in where the child is typing. Nothing is sent by it.
    private void addToMessage(CharSequence emoji) {
        Editable message = views.chatInput.getText();
        // At the cursor while the child is typing there; at the end otherwise.
        int at = views.chatInput.hasFocus()
                ? Math.max(0, views.chatInput.getSelectionStart()) : message.length();
        message.insert(at, emoji);
    }

    private void showPad(boolean open) {
        padOpen = open;
        backClosesThePad.setEnabled(open);
        if (open) {
            getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(views.chatInput.getWindowToken(), 0);
            // Each child starts with the crayon of their own colour.
            pickCrayon(session.state().getValue().typing);
        }
        render();
    }

    private void pickCrayon(int crayon) {
        views.chatCanvas.setColor(getColor(CRAYONS[crayon]));
        for (int i = 0; i < crayons.length; i++) {
            crayons[i].setBackgroundResource(i == crayon ? R.drawable.bg_kids_ring : 0);
            crayons[i].setBackgroundTintList(i == crayon
                    ? ColorStateList.valueOf(getColor(R.color.kids_ink)) : null);
            crayons[i].setSelected(i == crayon);
        }
    }

    // Asks the shape model about a stroke the child has just finished. Without the model, or
    // when it sees no shape, the stroke stays as it was drawn.
    private void tidy(Object stroke, List<float[]> points) {
        if (!app.models.isReady("shapes")) return;
        app.inference.execute(() -> {
            RecognizedShape shape;
            try {
                shape = app.shapes.run(points, ChatChecks.kidRules());
            } catch (Exception e) {
                return;
            }
            if (shape == null) return;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                views.chatCanvas.tidy(stroke, shape);
                views.chatPadHint.setText(getString(R.string.chat_pad_tidied, nameOf(shape)));
            });
        });
    }

    // A shape's name in words a young child knows.
    private String nameOf(RecognizedShape shape) {
        if ("ellipse".equals(shape.kind)) return getString(R.string.chat_shape_round);
        return getString(ShapeRecognizer.nameOf(shape.kind)).toLowerCase(Locale.ROOT);
    }

    private void sendDrawing() {
        session.sendDrawing(views.chatCanvas.render());
        views.chatCanvas.clear();
        showPad(false);
    }

    private void choosePicture() {
        pickPicture.launch(new PickVisualMediaRequest.Builder().setMediaType(
                ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    /** What the picker reports: the chosen picture, or null when it was closed without one. */
    public void onPicturePicked(@Nullable Uri uri) {
        if (uri != null) session.sendPicture(uri);
    }

    // A download is only ever started by this tap, as everywhere else in the app.
    private void downloadMissingModels() {
        for (String[] ids : new String[][] {SAFETY_MODELS, EXTRA_MODELS}) {
            for (String id : ids) {
                AssetState model = app.models.status(id).state;
                if (model == AssetState.NOT_INSTALLED || model == AssetState.FAILED) {
                    app.models.download(id);
                }
            }
        }
    }

    private void render() {
        State state = session.state().getValue();
        boolean ready = true;
        boolean fetching = false;
        for (String id : SAFETY_MODELS) {
            AssetState model = app.models.status(id).state;
            ready &= app.models.isReady(id);
            fetching |= model == AssetState.QUEUED || model == AssetState.DOWNLOADING;
        }
        // A card is up and waits for the child's answer: about private details in a message,
        // or about a person in a photo.
        boolean deciding = state.typed != null || state.pending != null;

        // What cannot be checked cannot be sent, so without the models there is no chat:
        // the getting-ready screen stands in for all of it.
        int chat = ready ? View.VISIBLE : View.GONE;
        views.chatLocked.setVisibility(ready ? View.GONE : View.VISIBLE);
        views.chatTop.setVisibility(chat);
        views.chatMiddle.setVisibility(chat);
        // The drawing pad stands in for the bar while it is open; the card for private
        // details comes before either.
        boolean pad = padOpen && ready && !deciding;
        views.chatPad.setVisibility(pad ? View.VISIBLE : View.GONE);
        views.chatBar.setVisibility(ready && !pad ? View.VISIBLE : View.GONE);
        views.chatSendDrawing.setEnabled(!views.chatCanvas.isEmpty() && !state.checking);

        boolean showIdeas = ready && !pad && !deciding && !state.ideas.isEmpty();
        views.chatIdeas.setVisibility(showIdeas ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ideas.length; i++) {
            boolean has = i < state.ideas.size();
            ideas[i].setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                ideas[i].setText(state.ideas.get(i));
                ideas[i].setContentDescription(
                        getString(R.string.chat_idea_add, state.ideas.get(i)));
            }
        }
        views.chatLockedText.setText(fetching ? R.string.chat_downloading : R.string.chat_locked);
        views.chatDownload.setEnabled(!fetching);

        boolean free = ready && !deciding && !state.checking;
        showTurn(state.typing, !deciding && !state.checking);
        list.show(state.messages, state.typing);
        views.chatEmpty.setVisibility(state.messages.isEmpty() ? View.VISIBLE : View.GONE);
        views.chatEmpty.setText(getString(R.string.chat_empty, name(1 - state.typing)));

        // Invisible, not gone: the conversation must not jump on every message.
        views.chatProgress.setVisibility(state.checking ? View.VISIBLE : View.INVISIBLE);
        boolean tip = ready && state.notice != 0;
        views.chatHelper.setVisibility(tip ? View.VISIBLE : View.GONE);
        if (tip) {
            views.chatNotice.setText(state.notice);
            views.chatNoticeMore.setText(whatToDoAbout(state.notice));
        }

        boolean cardWasUp = views.chatPersonal.getVisibility() == View.VISIBLE
                || views.chatPeople.getVisibility() == View.VISIBLE;
        if (deciding && !cardWasUp) {
            // The card needs the room, and the field is closed until a choice is made.
            getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(views.chatInput.getWindowToken(), 0);
            views.chatPersonal.scrollTo(0, 0);
            views.chatPeople.scrollTo(0, 0);
        }
        views.chatPeople.setVisibility(state.pending != null ? View.VISIBLE : View.GONE);
        views.chatPeoplePhoto.setImageBitmap(state.pending);
        views.chatPersonal.setVisibility(state.typed != null ? View.VISIBLE : View.GONE);
        // The safer message as it will look in the chat: the sender's colour, lock chips.
        views.chatPersonalMasked.setText(
                state.typed != null ? LockChipSpan.decorate(this, state.masked) : null);
        views.chatPersonalMasked.setBackgroundTintList(
                ColorStateList.valueOf(getColor(soft(state.typing))));

        // The field stays open during a check, so the keyboard does not drop after every
        // message; what was checked is what is sent, whatever is typed meanwhile.
        views.chatInput.setEnabled(ready && !deciding);
        views.chatSend.setEnabled(free);
        views.chatSend.setAlpha(free ? 1f : 0.4f);
        views.chatPicture.setEnabled(free);
        views.chatPicture.setAlpha(free ? 1f : 0.4f);
        views.chatDraw.setEnabled(free);
        views.chatDraw.setAlpha(free ? 1f : 0.4f);

        if (state.clearInput) {
            views.chatInput.setText("");
            session.inputCleared();
        }
    }

    // The child whose turn it is has the big face with a ring in their colour, and the
    // words beside it lean towards them.
    private void showTurn(int typing, boolean canSwap) {
        views.chatTurn.setText(getString(R.string.chat_turn, name(typing)));
        views.chatTurnHint.setText(getString(R.string.chat_turn_hint, name(1 - typing)));
        int lean = typing == 0 ? Gravity.START : Gravity.END;
        views.chatTurn.setGravity(lean);
        views.chatTurnHint.setGravity(lean);
        showFace(views.chatUserOne, 0, typing == 0, canSwap);
        showFace(views.chatUserTwo, 1, typing == 1, canSwap);
    }

    private void showFace(ImageButton face, int who, boolean theirTurn, boolean canSwap) {
        int size = Math.round((theirTurn ? FACE_BIG_DP : FACE_SMALL_DP)
                * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams box = face.getLayoutParams();
        if (box.width != size) {
            box.width = size;
            box.height = size;
            face.setLayoutParams(box);
        }
        face.setBackgroundResource(theirTurn ? R.drawable.bg_kids_ring : 0);
        face.setBackgroundTintList(
                theirTurn ? ColorStateList.valueOf(getColor(strong(who))) : null);
        face.setContentDescription(getString(
                theirTurn ? R.string.chat_typing_now : R.string.chat_let_type, name(who)));
        face.setEnabled(canSwap);
    }

    // The second line of a tip: what to do about it.
    private static int whatToDoAbout(int notice) {
        if (notice == R.string.chat_unkind) return R.string.chat_unkind_more;
        if (notice == R.string.chat_other_language) return R.string.chat_other_language_more;
        if (notice == R.string.chat_picture_refused) return R.string.chat_picture_refused_more;
        if (notice == R.string.chat_picture_unreadable) {
            return R.string.chat_picture_unreadable_more;
        }
        return R.string.chat_unchecked_more;
    }

    private String name(int who) {
        return getString(who == 0 ? R.string.chat_user_one : R.string.chat_user_two);
    }

    // Each child's own look: a face, a strong colour for its ring and a soft one for bubbles.
    private static int face(int who) {
        return who == 0 ? R.drawable.ic_kid_fox : R.drawable.ic_kid_bear;
    }

    private static int strong(int who) {
        return who == 0 ? R.color.kids_mia : R.color.kids_leo;
    }

    private static int soft(int who) {
        return who == 0 ? R.color.kids_mia_soft : R.color.kids_leo_soft;
    }

    /** The conversation as a list: messages are only ever added at the end. */
    private final class Messages extends RecyclerView.Adapter<Messages.Row> {

        final class Row extends RecyclerView.ViewHolder {
            final ItemChatMessageBinding item;

            Row(ItemChatMessageBinding item) {
                super(item.getRoot());
                this.item = item;
            }
        }

        private List<Message> shown = List.of();
        private int typing;

        void show(List<Message> messages, int typingNow) {
            int before = shown.size();
            boolean turned = typingNow != typing;
            shown = messages;
            typing = typingNow;
            // Whose side a message sits on depends on who is typing.
            if (turned && before > 0) notifyItemRangeChanged(0, before);
            if (messages.size() > before) {
                notifyItemRangeInserted(before, messages.size() - before);
                views.chatMessages.scrollToPosition(messages.size() - 1);
            }
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(ItemChatMessageBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Row row, int position) {
            Message message = shown.get(position);
            boolean mine = message.sender == typing;
            boolean words = message.text != null;
            ItemChatMessageBinding item = row.item;

            // Whoever is typing sees their own messages on the right, face on the outside.
            item.getRoot().setGravity((mine ? Gravity.END : Gravity.START) | Gravity.BOTTOM);
            item.chatFaceStart.setVisibility(mine ? View.GONE : View.VISIBLE);
            item.chatFaceEnd.setVisibility(mine ? View.VISIBLE : View.GONE);
            item.chatFaceStart.setImageResource(face(message.sender));
            item.chatFaceEnd.setImageResource(face(message.sender));
            // A message keeps its sender's colour whoever is looking, so a child who cannot
            // read the names still knows who said what. A picture sits in a white frame.
            item.chatBubble.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(words ? soft(message.sender) : R.color.kids_card)));
            float dp = getResources().getDisplayMetrics().density;
            int sides = Math.round((words ? 14 : 6) * dp);
            int ends = Math.round((words ? 10 : 6) * dp);
            item.chatBubble.setPadding(sides, ends, sides, ends);

            item.chatBubbleText.setVisibility(words ? View.VISIBLE : View.GONE);
            item.chatBubbleText.setText(
                    words ? LockChipSpan.decorate(ChatActivity.this, message.text) : null);
            item.chatBubblePicture.setVisibility(words ? View.GONE : View.VISIBLE);
            item.chatBubblePicture.setImageBitmap(message.picture);
            // A screen reader is told who it is from, also for the sender's own messages.
            item.chatBubbleText.setContentDescription(
                    getString(R.string.chat_said, name(message.sender), message.text));
            // What the picture model can see in a photo, under it and for a screen reader.
            boolean described = !words && !message.seen.isEmpty();
            String seen = described
                    ? getString(R.string.chat_seen, String.join(", ", message.seen)) : null;
            item.chatBubbleSeen.setVisibility(described ? View.VISIBLE : View.GONE);
            item.chatBubbleSeen.setText(seen);
            String from = getString(R.string.chat_picture_from, name(message.sender));
            item.chatBubblePicture.setContentDescription(described ? from + ". " + seen : from);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}

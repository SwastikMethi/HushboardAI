// Kids Chat: a demo of a chat for children, with two pretend users on one phone. A message
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
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

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
import com.example.aikeyboard.databinding.ActivityChatBinding;
import com.example.aikeyboard.databinding.ItemChatMessageBinding;
import com.example.aikeyboard.media.ImageImport;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.ui.chat.ChatChecks.Outcome;
import com.example.aikeyboard.ui.chat.ChatChecks.TextVerdict;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class ChatActivity extends AppCompatActivity {

    // Every one of these must be installed before anything can be sent.
    private static final String[] SAFETY_MODELS = {"toxic", "redact", "moderator"};

    /** One message in the conversation. */
    static final class Message {
        /** Who sent it: 0 or 1. */
        final int sender;
        /** The words, or null for a picture. */
        final String text;
        /** The picture, or null for words. */
        final Bitmap picture;

        Message(int sender, String text, Bitmap picture) {
            this.sender = sender;
            this.text = text;
            this.picture = picture;
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

        State(List<Message> messages, int typing, boolean checking, int notice, String typed,
                String masked, boolean clearInput) {
            this.messages = messages;
            this.typing = typing;
            this.checking = checking;
            this.notice = notice;
            this.typed = typed;
            this.masked = masked;
            this.clearInput = clearInput;
        }
    }

    /** The conversation and its checks; outlives rotation and ends when the screen is left. */
    public static final class Session extends AndroidViewModel {

        private final AppContainer app;
        private final Executor main;
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
        // Set when the screen is left for good: a check still running reports to nobody.
        private boolean left;

        public Session(@NonNull Application application) {
            super(application);
            app = AppContainer.from(application);
            main = ContextCompat.getMainExecutor(application);
            checks = ChatChecks.with(app, application, ChatChecks.kidRules());
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
                    main.execute(() -> pictureChecked(null, R.string.image_unreadable));
                    return;
                }
                app.inference.execute(() -> {
                    boolean allowed = checks.pictureAllowed(picture);
                    main.execute(() -> pictureChecked(allowed ? picture : null,
                            R.string.chat_picture_refused));
                });
            });
        }

        // How a picture's check ends: it joins the conversation, or the sender is told why not.
        private void pictureChecked(Bitmap allowed, int refusal) {
            if (left) return;
            checking = false;
            if (allowed != null) messages.add(new Message(typing, null, allowed));
            else notice = refusal;
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
            messages.add(new Message(typing, text, null));
            clearInput = true;
        }

        private boolean busy() {
            return checking || typed != null;
        }

        private void publish() {
            state.setValue(new State(List.copyOf(messages), typing, checking, notice, typed,
                    masked, clearInput));
        }

        @Override
        protected void onCleared() {
            left = true;
        }
    }

    private ActivityChatBinding views;
    private AppContainer app;
    private Session session;
    private final Messages list = new Messages();

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
        views.chatWho.addOnButtonCheckedListener((group, buttonId, checked) -> {
            if (checked) session.typeAs(buttonId == R.id.chat_user_two ? 1 : 0);
        });
        views.chatSend.setOnClickListener(
                v -> session.send(views.chatInput.getText().toString()));
        views.chatSendMasked.setOnClickListener(v -> session.sendMasked());
        views.chatChange.setOnClickListener(v -> session.changeIt());
        views.chatSendWritten.setOnClickListener(v -> session.sendAsWritten());
        views.chatDownload.setOnClickListener(v -> downloadMissingModels());
        // Why a download is waiting or failed, and the way to stop or retry it, are on the
        // Models screen; Back returns here.
        views.chatModels.setOnClickListener(v -> startActivity(
                new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MODELS)));
        views.chatPicture.setOnClickListener(v -> pickPicture.launch(
                new PickVisualMediaRequest.Builder().setMediaType(
                        ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));

        session.state().observe(this, state -> render());
        app.models.statuses().observe(this, statuses -> render());
    }

    /** What the picker reports: the chosen picture, or null when it was closed without one. */
    public void onPicturePicked(@Nullable Uri uri) {
        if (uri != null) session.sendPicture(uri);
    }

    // A download is only ever started by this tap, as everywhere else in the app.
    private void downloadMissingModels() {
        for (String id : SAFETY_MODELS) {
            AssetState model = app.models.status(id).state;
            if (model == AssetState.NOT_INSTALLED || model == AssetState.FAILED) {
                app.models.download(id);
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
        boolean deciding = state.typed != null;

        // What cannot be checked cannot be sent, so without the models there is no chat.
        views.chatLocked.setVisibility(ready ? View.GONE : View.VISIBLE);
        views.chatLockedText.setText(fetching ? R.string.chat_downloading : R.string.chat_locked);
        views.chatDownload.setEnabled(!fetching);

        list.show(state.messages, state.typing);
        views.chatEmpty.setVisibility(
                ready && state.messages.isEmpty() ? View.VISIBLE : View.GONE);

        // Invisible, not gone: the conversation must not jump on every message.
        views.chatProgress.setVisibility(state.checking ? View.VISIBLE : View.INVISIBLE);
        views.chatNotice.setVisibility(state.notice == 0 ? View.GONE : View.VISIBLE);
        if (state.notice != 0) views.chatNotice.setText(state.notice);

        if (deciding && views.chatPersonal.getVisibility() != View.VISIBLE) {
            // The card needs the room, and the field is closed until a choice is made.
            getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(views.chatInput.getWindowToken(), 0);
            views.chatPersonal.scrollTo(0, 0);
        }
        views.chatPersonal.setVisibility(deciding ? View.VISIBLE : View.GONE);
        views.chatPersonalTyped.setText(state.typed);
        views.chatPersonalMasked.setText(state.masked);

        // The field stays open during a check, so the keyboard does not drop after every
        // message; what was checked is what is sent, whatever is typed meanwhile.
        views.chatInput.setEnabled(ready && !deciding);
        boolean free = ready && !deciding && !state.checking;
        views.chatSend.setEnabled(free);
        views.chatPicture.setEnabled(free);
        views.chatPicture.setAlpha(free ? 1f : 0.4f);
        views.chatUserOne.setEnabled(!deciding && !state.checking);
        views.chatUserTwo.setEnabled(!deciding && !state.checking);
        views.chatWho.check(state.typing == 0 ? R.id.chat_user_one : R.id.chat_user_two);

        if (state.clearInput) {
            views.chatInput.setText("");
            session.inputCleared();
        }
    }

    private String name(int who) {
        return getString(who == 0 ? R.string.chat_user_one : R.string.chat_user_two);
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
            ItemChatMessageBinding item = row.item;

            FrameLayout.LayoutParams side =
                    (FrameLayout.LayoutParams) item.chatBubble.getLayoutParams();
            side.gravity = mine ? Gravity.END : Gravity.START;
            item.chatBubble.setLayoutParams(side);
            item.chatBubble.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(mine ? R.color.accent_blue : R.color.surface_raised)));

            item.chatBubbleName.setVisibility(mine ? View.GONE : View.VISIBLE);
            item.chatBubbleName.setText(name(message.sender));
            boolean words = message.text != null;
            item.chatBubbleText.setVisibility(words ? View.VISIBLE : View.GONE);
            item.chatBubbleText.setText(message.text);
            item.chatBubbleText.setTextColor(
                    getColor(mine ? R.color.on_accent_blue : R.color.text_primary));
            item.chatBubblePicture.setVisibility(words ? View.GONE : View.VISIBLE);
            item.chatBubblePicture.setImageBitmap(message.picture);
            // A screen reader is told who it is from, also for the sender's own messages.
            item.chatBubbleText.setContentDescription(
                    getString(R.string.chat_said, name(message.sender), message.text));
            item.chatBubblePicture.setContentDescription(
                    getString(R.string.chat_picture_from, name(message.sender)));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}

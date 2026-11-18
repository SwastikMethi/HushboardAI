// Image tools: the user picks a picture with the system's own picker, the image models that
// are switched on and installed look at it, and the result is shown beside the picture. The
// app sees only the picture that was picked. A flagged picture can still be shared, after a
// warning; the choice stays with the user.
package com.example.aikeyboard.ui.tools;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.databinding.FragmentImageToolsBinding;
import com.example.aikeyboard.media.ImageImport;
import com.example.aikeyboard.media.ShareResultController;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.media.Vision.Tag;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public final class ImageToolsFragment extends Fragment {

    private static final int TAGS_SHOWN = 6;

    /** What the screen shows. Replaced as a whole, on the main thread only. */
    public static final class State {
        public final boolean working;
        /** The picked picture, scaled to fit memory; null until one is picked. */
        public final Bitmap picture;
        final ImageFindings findings;
        final Sensitivity sensitivity;
        /** Ids of the models that were tried on this picture and failed. */
        final List<String> failed;
        /** One line of status in the app's own words, or 0. */
        final int message;

        State(boolean working, Bitmap picture, ImageFindings findings, Sensitivity sensitivity,
                List<String> failed, int message) {
            this.working = working;
            this.picture = picture;
            this.findings = findings;
            this.sensitivity = sensitivity;
            this.failed = failed;
            this.message = message;
        }

        public boolean analysed() {
            return picture != null && !working;
        }

        /** Whether the sensitive-content check ran on this picture and flagged it. */
        public boolean flagged() {
            return sensitivity != null && sensitivity.flagged;
        }
    }

    /**
     * The work behind the screen; outlives rotation and ends when the screen is left. The
     * keyboard's picture panel uses one too, as a plain object it lets go of with forget().
     */
    public static final class Session extends AndroidViewModel {

        private final AppContainer app;
        private final Executor main;
        private final MutableLiveData<State> state =
                new MutableLiveData<>(new State(false, null, null, null, List.of(), 0));
        // Set when a newer pick replaces this one, or the screen goes: its result is dropped.
        private AtomicBoolean abandoned = new AtomicBoolean();

        public Session(@NonNull Application application) {
            super(application);
            app = AppContainer.from(application);
            main = ContextCompat.getMainExecutor(application);
        }

        public LiveData<State> state() {
            return state;
        }

        /** A picture that has already been read, as the keyboard's picker screen hands over. */
        public void open(Bitmap picture) {
            abandoned.set(true);
            AtomicBoolean run = new AtomicBoolean();
            abandoned = run;
            analyse(picture, run);
        }

        /** Lets go of the picture and of anything still being worked out about it. */
        public void forget() {
            abandoned.set(true);
            state.setValue(new State(false, null, null, null, List.of(), 0));
        }

        void open(Uri uri) {
            abandoned.set(true);
            AtomicBoolean run = new AtomicBoolean();
            abandoned = run;
            State before = state.getValue();
            state.setValue(new State(true, before.picture, before.findings,
                    before.sensitivity, before.failed, 0));
            app.io.execute(() -> {
                Bitmap picture;
                try {
                    picture = ImageImport.decode(getApplication(), uri, ImageImport.MAX_SIDE);
                } catch (IOException e) {
                    // Whatever was on screen before stays; only the reason is added.
                    main.execute(() -> {
                        if (!run.get()) {
                            state.setValue(new State(false, before.picture, before.findings,
                                    before.sensitivity, before.failed,
                                    R.string.image_unreadable));
                        }
                    });
                    return;
                }
                main.execute(() -> {
                    if (!run.get()) analyse(picture, run);
                });
            });
        }

        private void analyse(Bitmap picture, AtomicBoolean run) {
            state.setValue(new State(true, picture, null, null, List.of(), 0));
            AppSettings settings = app.settings.latest();
            boolean eye = usable(app, settings, "eye");
            boolean moderator = usable(app, settings, "moderator");
            // The one inference thread: this and suggestions while typing take turns.
            app.inference.execute(() -> {
                ImageFindings findings = null;
                Sensitivity sensitivity = null;
                List<String> failed = new ArrayList<>();
                if (eye && !run.get()) {
                    try {
                        findings = app.eye.run(picture, settings);
                    } catch (Exception e) {
                        failed.add("eye");
                    }
                }
                if (moderator && !run.get()) {
                    try {
                        sensitivity = app.moderator.run(picture, settings);
                    } catch (Exception e) {
                        failed.add("moderator");
                    }
                }
                ImageFindings found = findings;
                Sensitivity checked = sensitivity;
                main.execute(() -> {
                    if (!run.get()) {
                        state.setValue(new State(false, picture, found, checked, failed, 0));
                    }
                });
            });
        }

        void note(int message) {
            State now = state.getValue();
            state.setValue(new State(now.working, now.picture, now.findings, now.sensitivity,
                    now.failed, message));
        }

        @Override
        protected void onCleared() {
            abandoned.set(true);
        }
    }

    public static boolean usable(AppContainer app, AppSettings settings, String modelId) {
        return settings.modelActive(modelId) && app.models.isReady(modelId);
    }

    private FragmentImageToolsBinding views;
    private AppContainer app;
    private Session session;
    private AppSettings settings;
    private Disposable settingsSubscription;

    // The system's photo picker: the app is handed the one picture chosen and nothing else,
    // and needs no storage permission.
    private final ActivityResultLauncher<PickVisualMediaRequest> pickPicture =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    this::onPicturePicked);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        app = AppContainer.from(requireContext());
        settings = app.settings.latest();
        session = new ViewModelProvider(this).get(Session.class);
        views = FragmentImageToolsBinding.inflate(inflater, container, false);
        views.imageChoose.setOnClickListener(v -> pickPicture.launch(
                new PickVisualMediaRequest.Builder().setMediaType(
                        ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        views.imageShare.setOnClickListener(v -> share());
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        session.state().observe(getViewLifecycleOwner(), state -> render());
        app.models.statuses().observe(getViewLifecycleOwner(), statuses -> render());
        // Disposed with the view; the stream arrives on a background thread.
        settingsSubscription = app.settings.observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(latest -> {
                    settings = latest;
                    render();
                });
    }

    @Override
    public void onDestroyView() {
        settingsSubscription.dispose();
        views = null;
        super.onDestroyView();
    }

    /** What the picker reports: the chosen picture, or null when it was closed without one. */
    public void onPicturePicked(@Nullable Uri uri) {
        if (uri != null) session.open(uri);
    }

    private void render() {
        if (views == null) return;
        State state = session.state().getValue();
        List<String> steps = new ArrayList<>();
        if (usable(app, settings, "eye")) steps.add(getString(R.string.image_step_eye));
        if (usable(app, settings, "moderator")) {
            steps.add(getString(R.string.image_step_moderator));
        }
        views.imageSteps.setText(!settings.aiEnabled() ? getString(R.string.tools_paused)
                : steps.isEmpty() ? getString(R.string.image_no_steps)
                : getString(R.string.voice_steps, String.join(", ", steps)));
        views.imageChoose.setEnabled(!steps.isEmpty() && !state.working);
        views.imageProgress.setVisibility(state.working ? View.VISIBLE : View.GONE);
        views.imageStatus.setText(state.working ? getString(R.string.image_working)
                : state.message == 0 ? "" : getString(state.message));

        views.imagePreview.setVisibility(state.picture == null ? View.GONE : View.VISIBLE);
        views.imagePreview.setImageBitmap(state.picture);
        views.imageResult.setVisibility(state.analysed() ? View.VISIBLE : View.GONE);
        if (state.analysed()) {
            showResult(state, views.imageKind, views.imageTags, views.imageDetails,
                    views.imageSensitivity, views.imageNotes, R.string.image_note);
        }
    }

    /**
     * Writes what the image models found into five lines of text. The keyboard's picture
     * panel shows the same result in its own layout, so it comes through here too.
     *
     * @param closingNote the caution that ends the notes
     */
    public static void showResult(State state, TextView kind, TextView tagLine,
            TextView details, TextView check, TextView noteLines, int closingNote) {
        Context context = kind.getContext();
        ImageFindings findings = state.findings;
        int eyeRows = findings == null ? View.GONE : View.VISIBLE;
        kind.setVisibility(eyeRows);
        tagLine.setVisibility(eyeRows);
        details.setVisibility(eyeRows);
        if (findings != null) {
            kind.setText(context.getString(R.string.image_kind, readable(findings.kind)));
            List<String> tags = new ArrayList<>();
            for (Tag tag : findings.tags) {
                if (tags.size() == TAGS_SHOWN) break;
                tags.add(context.getString(R.string.image_tag, readable(tag.name),
                        Math.round(tag.confidence * 100)));
            }
            tagLine.setText(tags.isEmpty() ? context.getString(R.string.image_no_tags)
                    : context.getString(R.string.image_tags, String.join(", ", tags)));
            details.setText(context.getString(R.string.image_details, findings.people,
                    findings.pets, findings.faces, Math.round(findings.aesthetic * 100)));
        }

        Sensitivity sensitivity = state.sensitivity;
        check.setVisibility(sensitivity == null ? View.GONE : View.VISIBLE);
        if (sensitivity != null) {
            check.setText(sensitivity.flagged
                    ? context.getString(R.string.image_flagged,
                            Math.round(sensitivity.score * 100))
                    : context.getString(R.string.image_not_flagged));
        }

        List<String> notes = new ArrayList<>();
        for (String id : state.failed) {
            notes.add(context.getString(R.string.voice_step_failed, context.getString(
                    "eye".equals(id) ? R.string.image_step_eye
                            : R.string.image_step_moderator)));
        }
        notes.add(context.getString(closingNote));
        noteLines.setText(String.join("\n", notes));
    }

    // The models' own words use underscores: "black_and_white" reads better with spaces.
    private static String readable(String modelWord) {
        return modelWord.replace('_', ' ');
    }

    private void share() {
        State state = session.state().getValue();
        if (state.picture == null) return;
        if (state.sensitivity == null || !state.sensitivity.flagged) {
            shareNow(state.picture);
            return;
        }
        // A warning, not a block: what the user shares is their decision.
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.image_share_warning)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.image_share_anyway,
                        (dialog, which) -> shareNow(state.picture))
                .show();
    }

    private void shareNow(Bitmap picture) {
        Context context = requireContext().getApplicationContext();
        app.io.execute(() -> {
            try {
                Intent share = ShareResultController.shareIntent(
                        ShareResultController.writePicture(context, picture,
                                ShareResultController.JPEG), ShareResultController.JPEG);
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (isAdded()) startActivity(Intent.createChooser(share, null));
                });
            } catch (IOException e) {
                ContextCompat.getMainExecutor(context).execute(
                        () -> session.note(R.string.image_share_failed));
            }
        });
    }
}

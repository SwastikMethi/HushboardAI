// Voice tools: the user records or imports audio, the voice models that are switched on and
// installed run over it in order, and the result is previewed before anything is copied or
// shared. Audio and transcripts stay in memory; nothing leaves this screen without a tap.
package com.example.aikeyboard.ui.tools;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
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
import com.example.aikeyboard.databinding.FragmentVoiceToolsBinding;
import com.example.aikeyboard.media.AudioDecoder;
import com.example.aikeyboard.media.AudioPlayback;
import com.example.aikeyboard.media.ShareResultController;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.VoiceResult;
import com.example.aikeyboard.media.VoiceText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public final class VoiceToolsFragment extends Fragment {

    /**
     * The longest recording or import that is processed; anything longer is cut here. The
     * screen's wording says "two minutes" (voice_recording, voice_cut): change them together.
     */
    static final int MAX_SECONDS = 120;
    // The order the pipeline runs its steps in.
    private static final String[] STEPS = {"clear", "ear", "voz", "uhm", "who"};

    enum Phase { IDLE, RECORDING, WORKING, DONE }

    /** What the screen shows. Replaced as a whole, on the main thread only. */
    static final class State {
        final Phase phase;
        /** The model now running, or null while the file is still being read. */
        final String step;
        /** One line of status in the app's own words, or 0. */
        final int message;
        final VoiceResult result;

        State(Phase phase, String step, int message, VoiceResult result) {
            this.phase = phase;
            this.step = step;
            this.message = message;
            this.result = result;
        }
    }

    /**
     * The work behind the screen. It outlives rotation, so a long run is not lost when the
     * screen is rebuilt, and ends when the screen is really left.
     */
    public static final class Session extends AndroidViewModel {

        private final AppContainer app;
        private final Executor main;
        private final MutableLiveData<State> state =
                new MutableLiveData<>(new State(Phase.IDLE, null, 0, null));
        // Set when the current run is abandoned: its steps stop and its result is dropped.
        private AtomicBoolean abandoned = new AtomicBoolean();
        private final AudioPlayback playback = new AudioPlayback();
        private final MutableLiveData<Boolean> playing = new MutableLiveData<>(false);

        public Session(@NonNull Application application) {
            super(application);
            app = AppContainer.from(application);
            main = ContextCompat.getMainExecutor(application);
        }

        LiveData<State> state() {
            return state;
        }

        boolean record() {
            if (!app.capture.start(MAX_SECONDS, this::stopRecording)) return false;
            state.setValue(new State(Phase.RECORDING, null, 0, null));
            return true;
        }

        void stopRecording() {
            if (state.getValue().phase != Phase.RECORDING) return;
            AudioClip clip = app.capture.stop();
            if (clip == null || clip.seconds() < 0.5) {
                state.setValue(new State(Phase.IDLE, null, R.string.voice_too_short, null));
                return;
            }
            run(clip, begin(), 0);
        }

        void importAudio(Uri uri) {
            AtomicBoolean run = begin();
            app.io.execute(() -> {
                try {
                    AudioClip clip = AudioDecoder.decode(getApplication(), uri, MAX_SECONDS);
                    boolean cut = clip.seconds() >= MAX_SECONDS - 1;
                    main.execute(() -> {
                        if (!run.get()) run(clip, run, cut ? R.string.voice_cut : 0);
                    });
                } catch (IOException | RuntimeException e) {
                    main.execute(() -> {
                        if (!run.get()) {
                            state.setValue(new State(Phase.IDLE, null,
                                    R.string.voice_unreadable, null));
                        }
                    });
                }
            });
        }

        LiveData<Boolean> playing() {
            return playing;
        }

        /** Plays the cleaned audio from the start, or stops it if it is playing. */
        void togglePlayback() {
            VoiceResult result = state.getValue().result;
            if (playback.isPlaying() || result == null || result.enhanced == null) {
                stopPlayback();
                return;
            }
            playback.play(result.enhanced, () -> playing.setValue(false));
            playing.setValue(true);
        }

        void stopPlayback() {
            playback.stop();
            playing.setValue(false);
        }

        /** Stops whatever is happening: a recording is discarded, a run's result dropped. */
        void cancel(int message) {
            stopPlayback();
            abandoned.set(true);
            if (state.getValue().phase == Phase.RECORDING) app.capture.cancel();
            state.setValue(new State(Phase.IDLE, null, message, null));
        }

        /** Changes the status line and nothing else. */
        void note(int message) {
            State now = state.getValue();
            state.setValue(new State(now.phase, now.step, message, now.result));
        }

        private AtomicBoolean begin() {
            stopPlayback();
            abandoned.set(true);
            abandoned = new AtomicBoolean();
            state.setValue(new State(Phase.WORKING, null, 0, null));
            return abandoned;
        }

        private void run(AudioClip clip, AtomicBoolean run, int note) {
            AppSettings settings = app.settings.latest();
            Predicate<String> usable = usable(app, settings);
            // The one inference thread: a run here and suggestions while typing take turns.
            app.inference.execute(() -> {
                try {
                    VoiceResult result = app.voice.run(clip, settings, usable, run,
                            step -> main.execute(() -> {
                                if (!run.get()) {
                                    state.setValue(new State(Phase.WORKING, step, 0, null));
                                }
                            }));
                    main.execute(() -> {
                        if (!run.get()) {
                            state.setValue(new State(Phase.DONE, null, note, result));
                        }
                    });
                } catch (CancellationException e) {
                    // The screen already says it was cancelled.
                }
            });
        }

        @Override
        protected void onCleared() {
            // The screen is gone for good: no microphone, sound or result may outlive it.
            playback.stop();
            abandoned.set(true);
            if (state.getValue().phase == Phase.RECORDING) app.capture.cancel();
        }
    }

    static Predicate<String> usable(AppContainer app, AppSettings settings) {
        return id -> settings.modelActive(id) && app.models.isReady(id);
    }

    private FragmentVoiceToolsBinding views;
    private AppContainer app;
    private Session session;
    private AppSettings settings;
    private Disposable settingsSubscription;

    private final ActivityResultLauncher<Intent> askForMicrophone = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) startRecording();
            });
    private final ActivityResultLauncher<String> pickAudio = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) importAudio(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        app = AppContainer.from(requireContext());
        settings = app.settings.latest();
        session = new ViewModelProvider(this).get(Session.class);
        views = FragmentVoiceToolsBinding.inflate(inflater, container, false);

        views.voiceRecord.setOnClickListener(v -> {
            if (session.state().getValue().phase == Phase.RECORDING) {
                session.stopRecording();
            } else if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                // Asked for here, at the tap, and nowhere earlier.
                askForMicrophone.launch(
                        new Intent(requireContext(), MicPermissionActivity.class));
            }
        });
        views.voiceImport.setOnClickListener(v -> pickAudio.launch("audio/*"));
        views.voiceCancel.setOnClickListener(v -> session.cancel(R.string.voice_cancelled));
        views.voicePlay.setOnClickListener(v -> session.togglePlayback());
        views.voiceCopy.setOnClickListener(v -> copyTranscript());
        views.voiceShare.setOnClickListener(v -> shareCleanedAudio());
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        session.state().observe(getViewLifecycleOwner(), state -> render());
        session.playing().observe(getViewLifecycleOwner(), playing -> render());
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
    public void onStop() {
        super.onStop();
        // Leaving the app ends a recording and any playback; a rotation does not.
        if (requireActivity().isChangingConfigurations()) return;
        session.stopPlayback();
        if (session.state().getValue().phase == Phase.RECORDING) {
            session.cancel(R.string.voice_interrupted);
        }
    }

    @Override
    public void onDestroyView() {
        settingsSubscription.dispose();
        views = null;
        super.onDestroyView();
    }

    private void startRecording() {
        if (!session.record()) {
            session.cancel(R.string.voice_mic_unavailable);
        }
    }

    /** Runs the steps on a file the user picked. */
    public void importAudio(Uri uri) {
        session.importAudio(uri);
    }

    private void render() {
        if (views == null) return;
        State state = session.state().getValue();
        List<String> steps = new ArrayList<>();
        Predicate<String> usable = usable(app, settings);
        for (String id : STEPS) {
            if (usable.test(id)) steps.add(stepName(id));
        }
        boolean canRun = !steps.isEmpty();
        views.voiceSteps.setText(!settings.aiEnabled() ? getString(R.string.voice_paused)
                : canRun ? getString(R.string.voice_steps, String.join(", ", steps))
                : getString(R.string.voice_no_steps));

        boolean recording = state.phase == Phase.RECORDING;
        boolean working = state.phase == Phase.WORKING;
        boolean free = !recording && !working;
        views.voiceRecord.setText(recording ? R.string.voice_stop : R.string.voice_record);
        views.voiceRecord.setEnabled(recording || canRun && free);
        views.voiceImport.setEnabled(canRun && free);
        views.voiceProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        views.voiceCancel.setVisibility(free ? View.GONE : View.VISIBLE);
        views.voiceStatus.setText(recording ? getString(R.string.voice_recording)
                : working ? state.step == null ? getString(R.string.voice_reading)
                        : getString(R.string.voice_working, stepName(state.step))
                : state.message == 0 ? "" : getString(state.message));

        views.voiceResult.setVisibility(state.result == null ? View.GONE : View.VISIBLE);
        if (state.result != null) renderResult(state.result);
    }

    private void renderResult(VoiceResult result) {
        views.voiceLanguage.setVisibility(result.language == null ? View.GONE : View.VISIBLE);
        if (result.language != null) {
            String name = new Locale(result.language.language).getDisplayLanguage();
            // A guess is worded as a guess.
            views.voiceLanguage.setText(result.language.reliable
                    ? getString(R.string.voice_language, name)
                    : getString(R.string.voice_language_unsure, name));
        }
        String transcript = VoiceText.preview(result);
        views.voiceTranscript.setText(!transcript.isEmpty() ? transcript
                : getString(result.transcript == null ? R.string.voice_no_transcript
                        : R.string.voice_no_speech));

        List<String> notes = new ArrayList<>();
        if (!result.fillers.isEmpty()) {
            notes.add(getResources().getQuantityString(R.plurals.voice_fillers,
                    result.fillers.size(), result.fillers.size()));
        }
        if (result.enhanced != null) notes.add(getString(R.string.voice_cleaned));
        for (String failure : result.failures) {
            String id = failure.substring(0, failure.indexOf(':'));
            notes.add(getString(R.string.voice_step_failed, stepName(id)));
        }
        views.voiceNotes.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        views.voiceNotes.setText(String.join("\n", notes));
        views.voiceCopy.setVisibility(transcript.isEmpty() ? View.GONE : View.VISIBLE);
        views.voiceShare.setVisibility(result.enhanced == null ? View.GONE : View.VISIBLE);
        views.voicePlay.setVisibility(result.enhanced == null ? View.GONE : View.VISIBLE);
        views.voicePlay.setText(Boolean.TRUE.equals(session.playing().getValue())
                ? R.string.voice_stop_playing : R.string.voice_play);
    }

    private String stepName(String modelId) {
        // R ids are not compile-time constants under AGP 9, so this cannot be a switch.
        if ("clear".equals(modelId)) return getString(R.string.voice_step_clear);
        if ("ear".equals(modelId)) return getString(R.string.voice_step_ear);
        if ("voz".equals(modelId)) return getString(R.string.voice_step_voz);
        if ("uhm".equals(modelId)) return getString(R.string.voice_step_uhm);
        return getString(R.string.voice_step_who);
    }

    // The words as recognised, without the preview's brackets and speaker labels.
    private void copyTranscript() {
        VoiceResult result = session.state().getValue().result;
        if (result == null || result.transcript == null) return;
        requireContext().getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.voice_copy), result.transcript.text));
    }

    private void shareCleanedAudio() {
        VoiceResult result = session.state().getValue().result;
        if (result == null || result.enhanced == null) return;
        Context context = requireContext().getApplicationContext();
        app.io.execute(() -> {
            try {
                Intent share = ShareResultController.audioShareIntent(context, result.enhanced);
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (isAdded()) startActivity(Intent.createChooser(share, null));
                });
            } catch (IOException e) {
                ContextCompat.getMainExecutor(context).execute(
                        () -> session.note(R.string.voice_share_failed));
            }
        });
    }
}

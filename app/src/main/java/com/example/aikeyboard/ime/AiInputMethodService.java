// The system keyboard service: owns the keyboard view and the connection to the focused field.
// M0 spike version: a handful of keys plus the platform probes; M1 replaces the view.
package com.example.aikeyboard.ime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.emoji2.emojipicker.EmojiPickerView;

import com.example.aikeyboard.R;

public class AiInputMethodService extends InputMethodService {

    private static final String TAG = "AiKeyboard";

    private EditorBridge editor;

    @Override
    public void onCreate() {
        super.onCreate();
        editor = new EditorBridge(this::getCurrentInputConnection);
    }

    @Override
    public View onCreateInputView() {
        // A service has no Material theme of its own; Material widgets need one to inflate.
        Context themed = new ContextThemeWrapper(this, R.style.Theme_AiKeyboard);
        View root = LayoutInflater.from(themed).inflate(R.layout.keyboard_spike, null);

        root.findViewById(R.id.key_a).setOnClickListener(v -> editor.commitText("a"));
        root.findViewById(R.id.key_b).setOnClickListener(v -> editor.commitText("b"));
        root.findViewById(R.id.key_space).setOnClickListener(v -> editor.commitText(" "));
        root.findViewById(R.id.key_backspace).setOnClickListener(v -> editor.backspace());

        EmojiPickerView picker = root.findViewById(R.id.emoji_picker);
        picker.setOnEmojiPickedListener(item -> editor.commitText(item.getEmoji()));
        root.findViewById(R.id.key_emoji).setOnClickListener(v -> picker.setVisibility(
                picker.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        TextView status = root.findViewById(R.id.probe_status);
        root.findViewById(R.id.key_mic).setOnClickListener(v -> probeMicrophone(status));

        // From targetSdk 35 the IME window is edge-to-edge, so the keyboard pads itself clear
        // of the navigation area instead of relying on the system to do it.
        TextView insetLabel = root.findViewById(R.id.probe_inset);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            // (InputMethodService has its own inner Insets class, so the type is not named here.)
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            insetLabel.setText("inset bottom=" + bottom);
            return insets;
        });
        return root;
    }

    // Spike probe: can the service really record while the keyboard is on screen?
    private void probeMicrophone(TextView status) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("mic: no permission");
            return;
        }
        new Thread(() -> {
            int rate = 16000;
            int minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            String result;
            AudioRecord record = null;
            try {
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuffer, rate));
                record.startRecording();
                short[] buffer = new short[rate / 2];
                int read = record.read(buffer, 0, buffer.length);
                // The platform hands a blocked app silence rather than an error, so the
                // recording configuration is the only honest signal of real access.
                boolean silenced = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
                    silenced = config == null || config.isClientSilenced();
                }
                result = "mic: state=" + record.getRecordingState() + " read=" + read
                        + " silenced=" + silenced;
            } catch (RuntimeException e) {
                result = "mic: error " + e.getClass().getSimpleName();
            } finally {
                if (record != null) record.release();
            }
            String message = result;
            Log.i(TAG, message);
            status.post(() -> status.setText(message));
        }).start();
    }
}

// Records from the microphone into memory: mono, at the speech rate, up to a time limit.
// Only ever started by an explicit tap, and only while the app holds the microphone
// permission. Audio is never written to disk here.
package com.example.aikeyboard.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.example.aikeyboard.media.Voice.AudioClip;

public class AudioCaptureController {

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    // One recording at a time. The reader thread fills `samples`; `count` is published to
    // other threads only by joining the reader, in stop() and cancel().
    private AudioRecord record;
    private Thread reader;
    private volatile boolean reading;
    private short[] samples;
    private int count;

    public AudioCaptureController(Context appContext) {
        this.app = appContext;
    }

    /**
     * Starts recording. Returns false when the app may not use the microphone, when it
     * cannot be opened (another app may hold it), or when a recording is already running.
     *
     * @param onLimit called on the main thread if the limit is reached; the recording has
     *         stopped by itself and stop() returns what was captured
     */
    public synchronized boolean start(int maxSeconds, Runnable onLimit) {
        if (record != null) return false;
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        int rate = AudioPipeline.SPEECH_RATE;
        int minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) return false;
        AudioRecord opened;
        try {
            opened = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, rate / 2));
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
        if (opened.getState() != AudioRecord.STATE_INITIALIZED) {
            opened.release();
            return false;
        }
        opened.startRecording();
        if (opened.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            opened.release();
            return false;
        }
        record = opened;
        short[] buffer = new short[maxSeconds * rate];
        samples = buffer;
        count = 0;
        reading = true;
        reader = new Thread(() -> {
            int filled = 0;
            while (reading && filled < buffer.length) {
                int read = opened.read(buffer, filled,
                        Math.min(rate / 10, buffer.length - filled));
                if (read <= 0) break; // the system took the microphone away
                filled += read;
            }
            count = filled;
            if (reading && filled == buffer.length) main.post(onLimit);
        }, "audio-capture");
        reader.start();
        return true;
    }

    public synchronized boolean isRecording() {
        return record != null;
    }

    /** Ends the recording and returns it, or null when nothing was being recorded. */
    public synchronized AudioClip stop() {
        if (record == null) return null;
        finish();
        float[] mono = AudioPipeline.toMono(java.util.Arrays.copyOf(samples, count), 1);
        samples = null;
        return new AudioClip(mono, AudioPipeline.SPEECH_RATE);
    }

    /** Ends the recording and throws the audio away. Safe to call when not recording. */
    public synchronized void cancel() {
        if (record == null) return;
        finish();
        samples = null;
    }

    // Stops the reader before releasing the recorder it is reading from.
    private void finish() {
        reading = false;
        try {
            record.stop();
        } catch (IllegalStateException e) {
            // Already stopped by the system; releasing is all that is left to do.
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        record.release();
        record = null;
        reader = null;
    }
}

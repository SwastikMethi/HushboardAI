// Stands in for the microphone in device tests: "records" a clip prepared beforehand, so a
// test can know exactly what was said. The real recorder is covered by AudioCaptureTest.
package com.example.aikeyboard;

import androidx.test.core.app.ApplicationProvider;

import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.Voice.AudioClip;

final class FakeCapture extends AudioCaptureController {

    private final AudioClip clip;
    private volatile boolean recording;
    volatile boolean cancelled;

    FakeCapture(AudioClip clip) {
        super(ApplicationProvider.getApplicationContext());
        this.clip = clip;
    }

    @Override
    public boolean start(int maxSeconds, Runnable onLimit) {
        recording = true;
        cancelled = false;
        return true;
    }

    @Override
    public boolean isRecording() {
        return recording;
    }

    @Override
    public AudioClip stop() {
        if (!recording) return null;
        recording = false;
        return clip;
    }

    @Override
    public void cancel() {
        if (recording) cancelled = true;
        recording = false;
    }
}

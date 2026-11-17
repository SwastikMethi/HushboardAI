// Real speech for the voice tests without a microphone: Android's own text-to-speech writes
// a WAV file once per test process. Null when the device has no usable synthesiser, so the
// tests that need speech can skip instead of failing.
package com.example.aikeyboard;

import android.content.Context;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.core.app.ApplicationProvider;

import com.example.aikeyboard.media.AudioDecoder;
import com.example.aikeyboard.media.Voice.AudioClip;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class TestSpeech {

    static final String SPOKEN = "Hello, this is a test of speech recognition on this device. "
            + "The weather is nice today and I would like some coffee.";

    private static boolean tried;
    private static File wav;
    private static AudioClip clip;

    private TestSpeech() {}

    /** The spoken sentence as a WAV file, or null. */
    static synchronized File wav() throws Exception {
        if (!tried) {
            tried = true;
            wav = synthesise();
        }
        return wav;
    }

    /** The spoken sentence as the app's decoder reads it (mono 16 kHz), or null. */
    static synchronized AudioClip english() throws Exception {
        if (clip == null && wav() != null) {
            clip = AudioDecoder.decode(ApplicationProvider.getApplicationContext(),
                    Uri.fromFile(wav), 120);
        }
        return clip;
    }

    static AudioClip repeated(AudioClip clip, int times) {
        float[] samples = new float[clip.samples.length * times];
        for (int i = 0; i < times; i++) {
            System.arraycopy(clip.samples, 0, samples, i * clip.samples.length,
                    clip.samples.length);
        }
        return new AudioClip(samples, clip.sampleRate);
    }

    /** True when the text has one of the sentence's distinctive words. */
    static boolean recognised(String text) {
        String heard = text.toLowerCase(Locale.ROOT);
        return heard.contains("speech") || heard.contains("weather") || heard.contains("coffee");
    }

    private static File synthesise() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch ready = new CountDownLatch(1);
        int[] status = {TextToSpeech.ERROR};
        TextToSpeech tts = new TextToSpeech(context, s -> {
            status[0] = s;
            ready.countDown();
        });
        try {
            if (!ready.await(20, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
                return null;
            }
            if (tts.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) return null;
            File file = new File(context.getCacheDir(), "tts-test.wav");
            CountDownLatch done = new CountDownLatch(1);
            boolean[] ok = {false};
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override
                public void onError(String id) {
                    done.countDown();
                }
            });
            tts.synthesizeToFile(SPOKEN, null, file, "voice-test");
            boolean written = done.await(60, TimeUnit.SECONDS) && ok[0] && file.length() > 1000;
            return written ? file : null;
        } finally {
            tts.shutdown();
        }
    }
}

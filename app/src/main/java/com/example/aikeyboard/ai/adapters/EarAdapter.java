// Ear: guesses the language being spoken in a piece of audio. A hint, like Tongue's.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.LanguageHint;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.concurrent.TimeUnit;

import ai.desertant.ear.Detection;
import ai.desertant.ear.Ear;
import ai.desertant.ear.Options;

public final class EarAdapter implements ModelAdapter<AudioClip, LanguageHint> {

    private static final String ID = "ear";
    private static final long TIMEOUT_MINUTES = 2;

    private final ModelRuntimePool pool;

    public EarAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public LanguageHint run(AudioClip audio, AppSettings settings) throws Exception {
        Ear ear = pool.acquire(ID, Ear::new, Ear::isDownloaded);
        Detection detection = DesertAntClient.<Detection>run(
                c -> ear.identify(audio.samples, audio.sampleRate, new Options(), c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        // The model itself says whether its guess can be relied on.
        return new LanguageHint(detection.getLanguage(), detection.isReliable());
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

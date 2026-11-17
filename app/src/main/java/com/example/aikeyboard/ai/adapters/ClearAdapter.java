// Clear: speech enhancement. Returns cleaned-up audio at the rate the model produces.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.concurrent.TimeUnit;

import ai.desertant.clear.Clear;
import ai.desertant.clear.Options;
import ai.desertant.clear.Result;

public final class ClearAdapter implements ModelAdapter<AudioClip, AudioClip> {

    private static final String ID = "clear";
    private static final long TIMEOUT_MINUTES = 10;

    private final ModelRuntimePool pool;

    public ClearAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public AudioClip run(AudioClip audio, AppSettings settings) throws Exception {
        Clear clear = pool.acquire(ID, Clear::new, Clear::isDownloaded);
        // Strength is a real parameter of this model; everything else stays at its default.
        Options defaults = new Options();
        double strength = settings.integer(AppSettings.modelSettingKey(ID,
                ModelRegistry.CLEAR_STRENGTH), ModelRegistry.CLEAR_STRENGTH_DEFAULT) / 100.0;
        Options options = new Options(strength, defaults.getMastering(),
                defaults.getSampleRate(), defaults.getChannelMode());
        Result result = DesertAntClient.<Result>run(
                c -> clear.enhance(audio.samples, audio.sampleRate, options, c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        return new AudioClip(result.getSamples(), (int) Math.round(result.getSampleRate()));
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

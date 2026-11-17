// Uhm: finds filler words ("um", "uh") in audio. It only reports where they are; cutting
// audio is not something this model or this app does.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.FillerSpan;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import ai.desertant.uhm.Bias;
import ai.desertant.uhm.Filler;
import ai.desertant.uhm.Options;
import ai.desertant.uhm.Uhm;
import ai.desertant.uhm.UhmResult;
import ai.desertant.uhm.Word;

public final class UhmAdapter implements ModelAdapter<UhmAdapter.Input, List<FillerSpan>> {

    /** The audio, plus the transcript's timed words when a transcript exists. */
    public static final class Input {
        public final AudioClip audio;
        public final List<TimedWord> words;

        public Input(AudioClip audio, List<TimedWord> words) {
            this.audio = audio;
            this.words = words;
        }
    }

    private static final String ID = "uhm";
    private static final long TIMEOUT_MINUTES = 5;

    private final ModelRuntimePool pool;

    public UhmAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public List<FillerSpan> run(Input input, AppSettings settings) throws Exception {
        Uhm uhm = pool.acquire(ID, Uhm::new, Uhm::isDownloaded);
        Options defaults = new Options();
        Options options = new Options(bias(settings), defaults.getMinConfidence(),
                defaults.getMinDurationSec(), defaults.getMinOverlapFraction(),
                defaults.getSplitContainedWords());
        List<Word> words = new ArrayList<>();
        for (TimedWord word : input.words) {
            words.add(new Word(word.start, word.end, word.text));
        }
        UhmResult result = DesertAntClient.<UhmResult>run(c -> uhm.detect(
                input.audio.samples, input.audio.sampleRate, options, words, c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        List<FillerSpan> fillers = new ArrayList<>();
        for (Filler filler : result.getFillers()) {
            fillers.add(new FillerSpan(filler.getStart(), filler.getEnd(), filler.getType()));
        }
        return fillers;
    }

    // The model's own trade-off between missing fillers and flagging real words.
    private static Bias bias(AppSettings settings) {
        String stored = settings.string(AppSettings.modelSettingKey(ID, ModelRegistry.UHM_BIAS),
                ModelRegistry.UHM_BIAS_DEFAULT);
        try {
            return Bias.valueOf(stored.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Bias.BALANCED;
        }
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

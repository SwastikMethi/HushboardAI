// Voz: speech to text for audio the user recorded or imported.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.TimedWord;
import com.example.aikeyboard.media.Voice.Transcript;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ai.desertant.voz.Transcription;
import ai.desertant.voz.Voz;
import ai.desertant.voz.Word;

public final class VozAdapter implements ModelAdapter<AudioClip, Transcript> {

    private static final String ID = "voz";
    // Speech recognition runs slower than real time on modest hardware.
    private static final long TIMEOUT_MINUTES = 10;

    private final ModelRuntimePool pool;

    public VozAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public Transcript run(AudioClip audio, AppSettings settings) throws Exception {
        Voz voz = pool.acquire(ID, Voz::new, Voz::isDownloaded);
        Transcription result = DesertAntClient.<Transcription>run(
                c -> voz.transcribe(audio.samples, audio.sampleRate, c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        List<TimedWord> words = new ArrayList<>();
        for (Word word : result.getWords()) {
            words.add(new TimedWord(word.getText(), word.getStart(), word.getEnd()));
        }
        return new Transcript(result.getText().trim(), words);
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

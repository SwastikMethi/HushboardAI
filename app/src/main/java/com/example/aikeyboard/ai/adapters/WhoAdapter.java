// Who (vendor beta): labels which stretches of a recording belong to which speaker.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;
import com.example.aikeyboard.media.Voice.SpeakerTurn;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ai.desertant.who.Diarization;
import ai.desertant.who.Turn;
import ai.desertant.who.Who;

public final class WhoAdapter implements ModelAdapter<AudioClip, List<SpeakerTurn>> {

    private static final String ID = "who";
    private static final long TIMEOUT_MINUTES = 5;

    private final ModelRuntimePool pool;

    public WhoAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public List<SpeakerTurn> run(AudioClip audio, AppSettings settings) throws Exception {
        Who who = pool.acquire(ID, Who::new, Who::isDownloaded);
        Diarization result = DesertAntClient.<Diarization>run(
                c -> who.diarize(audio.samples, audio.sampleRate, c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        List<SpeakerTurn> turns = new ArrayList<>();
        for (Turn turn : result.getTurns()) {
            turns.add(new SpeakerTurn(turn.getSpeaker(), turn.getStart(), turn.getEnd()));
        }
        return turns;
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

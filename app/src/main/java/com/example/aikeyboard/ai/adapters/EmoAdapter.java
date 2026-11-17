// Emo: emoji suggestions for a short piece of text. Converts between the app's types and the
// vendor SDK's, and nothing else.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.EmojiCandidate;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import ai.desertant.emo.Emo;
import ai.desertant.emo.EmoSuggestion;
import ai.desertant.emo.EmojiSkinTone;

public final class EmoAdapter implements ModelAdapter<String, List<EmojiCandidate>> {

    private static final String ID = "emo";
    private static final long TIMEOUT_SECONDS = 20;

    private final ModelRuntimePool pool;

    public EmoAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public List<EmojiCandidate> run(String text, AppSettings settings) throws Exception {
        Emo emo = pool.acquire(ID, Emo::new, Emo::isDownloaded);
        int limit = settings.integer(
                AppSettings.modelSettingKey(ID, ModelRegistry.EMO_LIMIT), 3);
        EmojiSkinTone tone = skinTone(settings.string(
                AppSettings.modelSettingKey(ID, ModelRegistry.EMO_SKIN_TONE), "default"));
        // Blocks this (inference) thread only. If the wait times out, the native call still
        // finishes on its own; the next call simply queues behind it inside the SDK.
        List<EmoSuggestion> suggestions = DesertAntClient.<List<EmoSuggestion>>run(
                c -> emo.suggestions(text, limit, tone, c)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.answered(ID);
        List<EmojiCandidate> candidates = new ArrayList<>();
        for (EmoSuggestion suggestion : suggestions) {
            candidates.add(new EmojiCandidate(suggestion.getEmoji(), suggestion.getConfidence()));
        }
        return candidates;
    }

    @Override
    public void release() {
        pool.release(ID);
    }

    private static EmojiSkinTone skinTone(String stored) {
        try {
            return EmojiSkinTone.valueOf(stored.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EmojiSkinTone.DEFAULT;
        }
    }
}

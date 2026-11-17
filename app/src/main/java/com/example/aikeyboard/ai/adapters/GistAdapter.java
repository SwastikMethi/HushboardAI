// Gist: tags a piece of text with topics from the model's fixed list. Runs only when the
// user asks for it.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.TopicTag;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ai.desertant.gist.Gist;
import ai.desertant.gist.Topic;

public final class GistAdapter implements ModelAdapter<String, List<TopicTag>> {

    private static final String ID = "gist";
    private static final long TIMEOUT_SECONDS = 30;

    private final ModelRuntimePool pool;

    public GistAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public List<TopicTag> run(String text, AppSettings settings) throws Exception {
        Gist gist = pool.acquire(ID, Gist::new, Gist::isDownloaded);
        int limit = settings.integer(
                AppSettings.modelSettingKey(ID, ModelRegistry.GIST_LIMIT), 3);
        // The last argument is the score threshold; null keeps the model's own.
        List<Topic> topics = DesertAntClient.<List<Topic>>run(
                c -> gist.classify(text, limit, null, c)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.answered(ID);
        List<TopicTag> tags = new ArrayList<>();
        for (Topic topic : topics) {
            tags.add(new TopicTag(topic.getSlug(), topic.getName(), topic.getScore()));
        }
        return tags;
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

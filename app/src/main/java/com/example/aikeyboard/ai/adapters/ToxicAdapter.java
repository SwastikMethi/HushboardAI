// Toxic (vendor beta): scores a piece of text for hateful, abusive and threatening wording.
// Returns the raw scores with the model's own thresholds; what to flag is the app's policy.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.Suggestion.ToxicScores;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ai.desertant.toxic.Analysis;
import ai.desertant.toxic.Label;
import ai.desertant.toxic.Options;
import ai.desertant.toxic.Toxic;

public final class ToxicAdapter implements ModelAdapter<String, ToxicScores> {

    private static final String ID = "toxic";
    private static final long TIMEOUT_SECONDS = 20;

    private final ModelRuntimePool pool;

    public ToxicAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public ToxicScores run(String text, AppSettings settings) throws Exception {
        Toxic toxic = pool.acquire(ID, Toxic::new, Toxic::isDownloaded);
        // Default options: the model's shipped thresholds come back with the result.
        Analysis analysis = DesertAntClient.<Analysis>run(
                c -> toxic.analyze(text, new Options(), c)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.answered(ID);
        return new ToxicScores(contentOnly(analysis.getScores()),
                contentOnly(analysis.getThresholds()));
    }

    // Keeps the wording labels (hateful, abusive, threat). The model's "who is targeted"
    // labels are not surfaced by this app.
    private static Map<String, Double> contentOnly(Map<Label, Double> byLabel) {
        Map<String, Double> content = new HashMap<>();
        for (Map.Entry<Label, Double> entry : byLabel.entrySet()) {
            if (!entry.getKey().isTarget()) {
                content.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return content;
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

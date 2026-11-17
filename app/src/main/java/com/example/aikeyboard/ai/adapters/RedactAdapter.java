// Redact: finds personal information in a piece of text. Converts between the app's types
// and the vendor SDK's. The SDK's own masked string is not used: the app masks span by span
// so each replacement can be checked against the editor first.
package com.example.aikeyboard.ai.adapters;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.ai.RedactCategories;
import com.example.aikeyboard.ai.Suggestion.SensitiveSpan;
import com.example.aikeyboard.core.TextRangeMapper;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ai.desertant.redact.Options;
import ai.desertant.redact.Redact;
import ai.desertant.redact.Redaction;
import ai.desertant.redact.RedactionItem;

public final class RedactAdapter implements ModelAdapter<String, List<SensitiveSpan>> {

    private static final String ID = "redact";
    private static final long TIMEOUT_SECONDS = 20;

    private final ModelRuntimePool pool;

    public RedactAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public List<SensitiveSpan> run(String text, AppSettings settings) throws Exception {
        Set<String> labels = RedactCategories.labels(settings);
        if (labels.isEmpty()) return List.of(); // every category is off: nothing to look for

        Redact redact = pool.acquire(ID, Redact::new, Redact::isDownloaded);
        double minimumConfidence = settings.integer(AppSettings.modelSettingKey(ID,
                ModelRegistry.REDACT_MIN_CONFIDENCE), 60) / 100.0;
        Options options = new Options(minimumConfidence, labels);
        Redaction redaction = DesertAntClient.<Redaction>run(
                c -> redact.redaction(text, options, c)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.answered(ID);

        List<SensitiveSpan> spans = new ArrayList<>();
        for (RedactionItem item : redaction.getItems()) {
            String category = RedactCategories.categoryOf(item.getLabel());
            // The model's offsets are UTF-16 but can cut through a character.
            int[] range = TextRangeMapper.wholeCharacters(text, item.getStart(), item.getEnd());
            if (category == null || range == null) continue;
            spans.add(new SensitiveSpan(category, range[0], range[1],
                    text.substring(range[0], range[1]), item.getPlaceholder()));
        }
        return spans;
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

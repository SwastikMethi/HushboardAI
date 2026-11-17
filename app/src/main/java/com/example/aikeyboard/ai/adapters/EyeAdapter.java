// Eye (vendor beta): says what sort of picture this is, tags what is in it and scores how it
// looks. Only ever given a picture the user picked.
package com.example.aikeyboard.ai.adapters;

import android.graphics.Bitmap;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.ImageFindings;
import com.example.aikeyboard.media.Vision.Tag;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ai.desertant.eye.Analysis;
import ai.desertant.eye.Eye;
import ai.desertant.eye.Options;

public final class EyeAdapter implements ModelAdapter<Bitmap, ImageFindings> {

    private static final String ID = "eye";
    private static final long TIMEOUT_MINUTES = 2;

    private final ModelRuntimePool pool;

    public EyeAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public ImageFindings run(Bitmap picture, AppSettings settings) throws Exception {
        Eye eye = pool.acquire(ID, Eye::new, Eye::isDownloaded);
        Analysis analysis = DesertAntClient.<Analysis>run(
                c -> eye.analyze(picture, new Options(), c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        // The tags the model itself recommends showing, with its confidence in each.
        List<Tag> tags = new ArrayList<>();
        for (ai.desertant.eye.Tag tag : analysis.tags()) {
            Double confidence = analysis.confidence(tag);
            tags.add(new Tag(tag.getId(), confidence == null ? 0 : confidence));
        }
        tags.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return new ImageFindings(analysis.getKind().getId(), tags,
                analysis.getScores().getAesthetic(), analysis.getPeople().size(),
                analysis.getPets().size(), analysis.getFaces().size());
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

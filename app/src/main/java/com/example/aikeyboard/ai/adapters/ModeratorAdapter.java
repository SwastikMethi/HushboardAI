// Moderator: scores a picture the user picked for nudity, so the app can warn before it is
// shared. The score at which it flags is the model's own threshold parameter.
package com.example.aikeyboard.ai.adapters;

import android.graphics.Bitmap;

import com.example.aikeyboard.ai.ModelAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Vision.Sensitivity;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.util.concurrent.TimeUnit;

import ai.desertant.moderator.Moderation;
import ai.desertant.moderator.Moderator;
import ai.desertant.moderator.Options;

public final class ModeratorAdapter implements ModelAdapter<Bitmap, Sensitivity> {

    private static final String ID = "moderator";
    private static final long TIMEOUT_MINUTES = 2;

    private final ModelRuntimePool pool;

    public ModeratorAdapter(ModelRuntimePool pool) {
        this.pool = pool;
    }

    @Override
    public String modelId() {
        return ID;
    }

    @Override
    public Sensitivity run(Bitmap picture, AppSettings settings) throws Exception {
        Moderator moderator = pool.acquire(ID, Moderator::new, Moderator::isDownloaded);
        // Only the threshold is the user's; policy and quality stay at the vendor defaults.
        Options defaults = new Options();
        double threshold = settings.integer(AppSettings.modelSettingKey(ID,
                ModelRegistry.MODERATOR_THRESHOLD), ModelRegistry.MODERATOR_THRESHOLD_DEFAULT)
                / 100.0;
        Options options = new Options(threshold, defaults.getPolicy(), defaults.getQuality());
        Moderation result = DesertAntClient.<Moderation>run(
                c -> moderator.analyze(picture, options, c))
                .get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        pool.answered(ID);
        return new Sensitivity(result.getScore(), result.isNSFW());
    }

    @Override
    public void release() {
        pool.release(ID);
    }
}

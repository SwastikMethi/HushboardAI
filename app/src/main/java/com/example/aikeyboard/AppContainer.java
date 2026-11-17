// The app's shared objects, created once per process and handed to whoever needs them. The
// keyboard service, the companion Activity and the download worker all run in this process
// and all use the same instances.
package com.example.aikeyboard;

import android.content.Context;

import com.example.aikeyboard.ai.CrashBreaker;
import com.example.aikeyboard.ai.adapters.EmoAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AppContainer {

    public final SettingsRepository settings;
    public final ModelRepository models;
    /** File and network work. Never used for inference. */
    public final ExecutorService io = Executors.newFixedThreadPool(2);
    /** The one inference slot: every model call and every model load or release runs here. */
    public final ExecutorService inference =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "inference"));
    /** Debounce timers only; no work runs on this thread. */
    public final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    public final CrashBreaker breaker;
    public final ModelRuntimePool runtime;
    public final EmoAdapter emo;

    AppContainer(Context appContext) {
        DesertAntClient.silenceSdkLogs();
        settings = new SettingsRepository(appContext);
        models = new ModelRepository(appContext, settings, io);
        breaker = new CrashBreaker(new File(appContext.getNoBackupFilesDir(), "loading"));
        runtime = new ModelRuntimePool(appContext, models, breaker);
        emo = new EmoAdapter(runtime);
        // Files may only be deleted once the model using them has been closed.
        models.setRuntimeReleaser(this::releaseAndWait);
        disableModelsThatCrashed();
    }

    public static AppContainer from(Context context) {
        return ((KeyboardApplication) context.getApplicationContext()).container();
    }

    /**
     * Switches off any model whose load never finished in an earlier process, so one bad
     * model cannot take typing down again on every start. The user can turn it back on.
     */
    public void disableModelsThatCrashed() {
        for (String modelId : breaker.crashedLastTime()) {
            settings.setBoolean(AppSettings.modelEnabledKey(modelId), false);
            settings.setBoolean(AppSettings.modelCrashedKey(modelId), true);
        }
    }

    /** Frees every loaded model, for when the system is short of memory. */
    void releaseAllModels() {
        inference.execute(runtime::releaseAll);
    }

    // Queues the release behind any inference in progress and waits for it. Blocking.
    private void releaseAndWait(String modelId) {
        try {
            inference.submit(() -> runtime.release(modelId)).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            // Deleting proceeds; a model stuck this long is not coming back.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

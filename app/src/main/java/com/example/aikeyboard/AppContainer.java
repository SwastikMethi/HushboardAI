// The app's shared objects, created once per process and handed to whoever needs them. The
// keyboard service, the companion Activity and the download worker all run in this process
// and all use the same instances.
package com.example.aikeyboard;

import android.app.ActivityManager;
import android.content.Context;

import com.example.aikeyboard.ai.CrashBreaker;
import com.example.aikeyboard.ai.adapters.ClearAdapter;
import com.example.aikeyboard.ai.adapters.EarAdapter;
import com.example.aikeyboard.ai.adapters.EmoAdapter;
import com.example.aikeyboard.ai.adapters.EyeAdapter;
import com.example.aikeyboard.ai.adapters.ModeratorAdapter;
import com.example.aikeyboard.ai.adapters.ShapesAdapter;
import com.example.aikeyboard.ai.adapters.GistAdapter;
import com.example.aikeyboard.ai.adapters.RedactAdapter;
import com.example.aikeyboard.ai.adapters.TongueAdapter;
import com.example.aikeyboard.ai.adapters.ToxicAdapter;
import com.example.aikeyboard.ai.adapters.UhmAdapter;
import com.example.aikeyboard.ai.adapters.VozAdapter;
import com.example.aikeyboard.ai.adapters.WhoAdapter;
import com.example.aikeyboard.media.AudioCaptureController;
import com.example.aikeyboard.media.AudioPipeline;
import com.example.aikeyboard.media.ShareResultController;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRuntimePool;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AppContainer {

    public static final int TOOL_IDLE_SECONDS = 20;

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
    public final RedactAdapter redact;
    public final ToxicAdapter toxic;
    public final TongueAdapter tongue;
    public final GistAdapter gist;
    public final VozAdapter voz;
    public final EyeAdapter eye;
    public final ModeratorAdapter moderator;
    public final ShapesAdapter shapes;
    /**
     * The microphone. Not final so a device test can put known speech where a microphone
     * would be; the app itself never replaces it.
     */
    public AudioCaptureController capture;
    private ScheduledFuture<?> toolsIdle;
    /** The voice tools' chain: cleanup, language, transcript, fillers, speakers. */
    public final AudioPipeline voice;

    AppContainer(Context appContext) {
        DesertAntClient.silenceSdkLogs();
        settings = new SettingsRepository(appContext);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        appContext.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        models = new ModelRepository(appContext, settings, io, memory.totalMem);
        breaker = new CrashBreaker(new File(appContext.getNoBackupFilesDir(), "loading"));
        runtime = new ModelRuntimePool(appContext, models, breaker);
        runtime.setUseListener(this::releaseToolsWhenIdle);
        emo = new EmoAdapter(runtime);
        redact = new RedactAdapter(runtime);
        toxic = new ToxicAdapter(runtime);
        tongue = new TongueAdapter(appContext);
        gist = new GistAdapter(runtime);
        capture = new AudioCaptureController(appContext);
        voz = new VozAdapter(runtime);
        voice = new AudioPipeline(new ClearAdapter(runtime), new EarAdapter(runtime), voz,
                new UhmAdapter(runtime), new WhoAdapter(runtime));
        eye = new EyeAdapter(runtime);
        moderator = new ModeratorAdapter(runtime);
        shapes = new ShapesAdapter(runtime);
        // Results the user shared earlier are not kept around (see ShareResultController).
        io.execute(() -> ShareResultController.removeOldFiles(appContext));
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

    // The models used while typing stay loaded, because suggestions must be quick. The
    // voice, image and drawing models are used now and then and are heavy (Voz holds close to
    // 4 GB, Eye about 1.5 GB), so they are given back once none of them has been used for a
    // short while: long enough that a second dictation stays quick, short enough that the
    // keyboard does not sit on that memory. Called from the inference thread on every use.
    private synchronized void releaseToolsWhenIdle(String modelId) {
        if (ModelRegistry.find(modelId).group == ModelDescriptor.Group.TEXT) return;
        if (toolsIdle != null) toolsIdle.cancel(false);
        toolsIdle = timer.schedule(() -> inference.execute(() -> {
            for (ModelDescriptor model : ModelRegistry.all()) {
                if (model.group != ModelDescriptor.Group.TEXT) runtime.release(model.id);
            }
        }), TOOL_IDLE_SECONDS, TimeUnit.SECONDS);
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

// Owns each model's asset state: what is on disk, what is downloading, what failed and why.
// Asset state is separate from the user's enable switch (settings) and from whether a model
// is loaded in memory (runtime). A model is Ready only when every pinned file is in place.
package com.example.aikeyboard.models;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelDownloader.Failure;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class ModelRepository {

    public enum AssetState {
        /** Listed in the catalog, but this build has no adapter or file list for it yet. */
        UNAVAILABLE,
        /** Ships inside the app; nothing to download or delete. */
        INCLUDED,
        NOT_INSTALLED,
        /** Waiting to start or to retry (for example, for an allowed network). */
        QUEUED,
        DOWNLOADING,
        READY,
        FAILED,
        /** Files are in place but the vendor runtime on this device will not accept them. */
        INCOMPATIBLE
    }

    public static final class ModelStatus {
        public final AssetState state;
        public final long bytesDone;
        public final long bytesTotal;
        /** Why it failed or is waiting, or null. */
        public final Failure failure;

        ModelStatus(AssetState state, long bytesDone, long bytesTotal, Failure failure) {
            this.state = state;
            this.bytesDone = bytesDone;
            this.bytesTotal = bytesTotal;
            this.failure = failure;
        }
    }

    private static final long PROGRESS_INTERVAL_MS = 150;
    private static final int MAX_AUTOMATIC_RETRIES = 4;

    private final Context app;
    private final SettingsRepository settings;
    private final ExecutorService io;
    private final Map<String, ModelStatus> statuses = new ConcurrentHashMap<>();
    private final MutableLiveData<Map<String, ModelStatus>> live = new MutableLiveData<>();
    // Models whose transfer the user asked to stop, so a stopping worker knows to clean up.
    private final Set<String> cancelledByUser = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastProgressAt = new ConcurrentHashMap<>();

    public ModelRepository(Context appContext, SettingsRepository settings, ExecutorService io) {
        this.app = appContext;
        this.settings = settings;
        this.io = io;
        for (ModelDescriptor model : ModelRegistry.all()) {
            statuses.put(model.id, new ModelStatus(model.bundled ? AssetState.INCLUDED
                    : model.available() ? AssetState.NOT_INSTALLED : AssetState.UNAVAILABLE,
                    0, model.totalBytes(), null));
        }
        publish();
        io.execute(this::refreshFromDisk);
    }

    /** All statuses, for screens. Delivered on the main thread. */
    public LiveData<Map<String, ModelStatus>> statuses() {
        return live;
    }

    /** One model's current status, from any thread. */
    public ModelStatus status(String modelId) {
        return statuses.get(modelId);
    }

    /** Whether the model's assets may be loaded. The only gate inference paths may use. */
    public boolean isReady(String modelId) {
        ModelStatus status = statuses.get(modelId);
        return status != null
                && (status.state == AssetState.READY || status.state == AssetState.INCLUDED);
    }

    /** App-private, excluded from backup, and not purged by the system like the cache is. */
    public File directory(ModelDescriptor model) {
        return new File(app.getNoBackupFilesDir(), "models/" + model.id + "/" + model.revision);
    }

    /** Bytes this model currently occupies, including a partial download. */
    public long bytesOnDisk(String modelId) {
        return size(new File(app.getNoBackupFilesDir(), "models/" + modelId));
    }

    /**
     * Recomputes every model's state from the files themselves. Runs at process start (a
     * stored "ready" flag could outlive the files it describes). Blocking: not for the main
     * thread.
     */
    public void refreshFromDisk() {
        for (ModelDescriptor model : ModelRegistry.all()) {
            if (model.bundled || !model.available()) continue;
            if (ModelDownloader.isComplete(directory(model), model.files)) {
                set(model.id, AssetState.READY, model.totalBytes(), null);
            } else if (hasPendingWork(model)) {
                set(model.id, AssetState.QUEUED, 0, null);
            } else {
                set(model.id, AssetState.NOT_INSTALLED, 0, null);
            }
        }
    }

    private boolean hasPendingWork(ModelDescriptor model) {
        try {
            List<WorkInfo> infos = WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWork(workName(model)).get(5, TimeUnit.SECONDS);
            for (WorkInfo info : infos) if (!info.getState().isFinished()) return true;
        } catch (ExecutionException | InterruptedException
                 | java.util.concurrent.TimeoutException e) {
            // Unknown is treated as "not downloading"; the user can simply start it again.
        }
        return false;
    }

    /** Starts a download. Only ever called from a user action in the companion app. */
    public void download(String modelId) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        if (model == null || model.bundled || !model.available()) return;
        cancelledByUser.remove(modelId);
        set(modelId, AssetState.QUEUED, 0, null);
        AppSettings current = settings.latest();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(current.unmeteredOnly()
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(new Data.Builder()
                        .putString(ModelDownloadWorker.KEY_MODEL, modelId).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build();
        // Unique per model revision: a second tap cannot start a second writer.
        WorkManager.getInstance(app)
                .enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request);
    }

    /** Stops a download and removes what it had fetched. */
    public void cancel(String modelId) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        if (model == null) return;
        cancelledByUser.add(modelId);
        WorkManager.getInstance(app).cancelUniqueWork(workName(model));
        set(modelId, AssetState.NOT_INSTALLED, 0, null);
        // If the worker had not started, nobody else will clean up.
        io.execute(() -> {
            if (cancelledByUser.contains(modelId)) deleteRecursively(directory(model));
        });
    }

    /** Turns the model off and removes its files. Files stay if only disabled. */
    public void delete(String modelId) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        if (model == null || model.bundled) return;
        settings.setBoolean(AppSettings.modelEnabledKey(modelId), false);
        cancelledByUser.add(modelId);
        WorkManager.getInstance(app).cancelUniqueWork(workName(model));
        set(modelId, AssetState.NOT_INSTALLED, 0, null);
        io.execute(() -> deleteRecursively(
                new File(app.getNoBackupFilesDir(), "models/" + modelId)));
    }

    /** The runtime found the installed files unusable on this device. */
    public void markIncompatible(String modelId) {
        set(modelId, AssetState.INCOMPATIBLE, 0, null);
    }

    // ---- Called by ModelDownloadWorker, on a background thread ----

    void onProgress(String modelId, long done, long total) {
        long now = System.currentTimeMillis();
        Long last = lastProgressAt.get(modelId);
        if (last != null && now - last < PROGRESS_INTERVAL_MS && done < total) return;
        lastProgressAt.put(modelId, now);
        if (cancelledByUser.contains(modelId)) return;
        ModelDescriptor model = ModelRegistry.find(modelId);
        statuses.put(modelId, new ModelStatus(AssetState.DOWNLOADING, done, total, null));
        publish();
    }

    void onFinished(String modelId) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        set(modelId, AssetState.READY, model.totalBytes(), null);
    }

    /**
     * The transfer stopped without finishing. Returns true when WorkManager should try again
     * later (a stop by the system, or a network failure that may be passing).
     */
    boolean onStopped(String modelId, Failure failure, int attempt) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        if (failure == Failure.CANCELLED) {
            if (cancelledByUser.remove(modelId)) {
                deleteRecursively(directory(model));
                set(modelId, AssetState.NOT_INSTALLED, 0, null);
                return false;
            }
            // Stopped by the system (network no longer allowed, or the time limit): the
            // partial file stays and the work resumes when it is allowed to run again.
            set(modelId, AssetState.QUEUED, 0, null);
            return true;
        }
        if (failure == Failure.NETWORK && attempt < MAX_AUTOMATIC_RETRIES) {
            set(modelId, AssetState.QUEUED, 0, Failure.NETWORK);
            return true;
        }
        set(modelId, AssetState.FAILED, 0, failure);
        return false;
    }

    private void set(String modelId, AssetState state, long done, Failure failure) {
        ModelDescriptor model = ModelRegistry.find(modelId);
        statuses.put(modelId, new ModelStatus(state, done, model.totalBytes(), failure));
        publish();
    }

    private void publish() {
        live.postValue(Collections.unmodifiableMap(new HashMap<>(statuses)));
    }

    private static String workName(ModelDescriptor model) {
        return "download-" + model.id + "-" + model.revision;
    }

    private static long size(File file) {
        File[] children = file.listFiles();
        if (children == null) return file.length();
        long total = 0;
        for (File child : children) total += size(child);
        return total;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}

// Runs one model download under WorkManager, so it survives the app being closed and stops
// when the network it was allowed to use goes away.
package com.example.aikeyboard.models;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.models.ModelDownloader.DownloadException;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelDownloadWorker extends ListenableWorker {

    static final String KEY_MODEL = "model";

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        // startWork runs on the main thread: hand the transfer to a background thread and
        // return the future at once. Nothing here may block.
        return CallbackToFutureAdapter.getFuture(completer -> {
            AppContainer container = AppContainer.from(getApplicationContext());
            container.downloads.execute(() -> completer.set(download(container.models)));
            return "model download";
        });
    }

    private Result download(ModelRepository repository) {
        String modelId = getInputData().getString(KEY_MODEL);
        ModelDescriptor model = modelId == null ? null : ModelRegistry.find(modelId);
        if (model == null || model.bundled || !model.available()) return Result.failure();
        try {
            new ModelDownloader().fetch(model.baseUrl(), model.files,
                    repository.directory(model), cancelled,
                    (done, total) -> repository.onProgress(modelId, done, total));
            repository.onFinished(modelId);
            return Result.success();
        } catch (DownloadException e) {
            boolean retry = repository.onStopped(modelId, e.failure, getRunAttemptCount());
            return retry ? Result.retry() : Result.failure();
        }
    }

    @Override
    public void onStopped() {
        // The user cancelled, the allowed network went away, or WorkManager's time limit hit.
        // The downloader notices within one buffer and stops; partial bytes stay for a resume.
        cancelled.set(true);
    }
}

// Loaded model instances. Models load lazily on first use, are reused, and are closed when
// deleted, when the tools they belong to have gone unused for a while, or when memory is
// short. (Switching a typing model off stops it being called; it stays loaded.) Every method
// runs on the single inference thread, which is what serialises access to each native model:
// nothing here needs a lock.
package com.example.aikeyboard.models;

import android.content.Context;

import com.example.aikeyboard.ai.CrashBreaker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ModelRuntimePool {

    /** How a vendor model class is constructed: context plus the directory holding its files. */
    public interface Factory<M> {
        M create(Context context, String directory);
    }

    /** The model cannot be used right now; callers show nothing and keep typing working. */
    public static final class ModelUnavailableException extends Exception {
        ModelUnavailableException(String message) {
            super(message);
        }
    }

    private final Context app;
    private final ModelRepository models;
    private final CrashBreaker breaker;
    private final Map<String, AutoCloseable> loaded = new HashMap<>();
    private final Set<String> answered = new HashSet<>();
    private volatile Consumer<String> onUse = modelId -> { };

    public ModelRuntimePool(Context appContext, ModelRepository models, CrashBreaker breaker) {
        this.app = appContext;
        this.models = models;
        this.breaker = breaker;
    }

    /** Told a model's id each time it is about to be used, loaded already or not. */
    public void setUseListener(Consumer<String> listener) {
        onUse = listener;
    }

    /**
     * The loaded instance, loading it first if needed. A model is only ever constructed from
     * verified files: constructing one over missing files would let the SDK start a download.
     */
    @SuppressWarnings("unchecked") // Each id is always loaded through the same factory type.
    public <M extends AutoCloseable> M acquire(String modelId, Factory<M> factory,
            Predicate<M> filesAccepted) throws ModelUnavailableException {
        onUse.accept(modelId);
        AutoCloseable existing = loaded.get(modelId);
        if (existing != null) return (M) existing;
        if (!models.isReady(modelId)) {
            throw new ModelUnavailableException(modelId + " is not installed");
        }
        // If the native load kills the process, the marker tells the next start which model.
        breaker.loading(modelId);
        M model;
        try {
            model = factory.create(app,
                    models.directory(ModelRegistry.find(modelId)).getAbsolutePath());
        } catch (RuntimeException e) {
            // The load failed, but the process is alive to say so: this is not a crash.
            breaker.loaded(modelId);
            throw e;
        }
        if (!filesAccepted.test(model)) {
            closeQuietly(model);
            breaker.loaded(modelId);
            models.markIncompatible(modelId);
            throw new ModelUnavailableException(modelId + " files were not accepted");
        }
        loaded.put(modelId, model);
        return model;
    }

    /** The model produced a real answer, so its load is proven safe. */
    public void answered(String modelId) {
        if (answered.add(modelId)) breaker.loaded(modelId);
    }

    public void release(String modelId) {
        closeQuietly(loaded.remove(modelId));
        // Its next load is marked again, so its next answer must clear that mark again.
        answered.remove(modelId);
    }

    public void releaseAll() {
        for (AutoCloseable model : loaded.values()) closeQuietly(model);
        loaded.clear();
        answered.clear();
    }

    private static void closeQuietly(AutoCloseable model) {
        if (model == null) return;
        try {
            model.close();
        } catch (Exception e) {
            // Nothing useful can be done about a failed close.
        }
    }
}

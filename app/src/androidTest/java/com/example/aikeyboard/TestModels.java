// Test helpers for reaching the app's shared objects and waiting on model state.
package com.example.aikeyboard;

import android.content.Context;
import android.net.ConnectivityManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.models.ModelRepository.ModelStatus;

import org.junit.Assume;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class TestModels {

    private TestModels() {}

    static AppContainer container() {
        return AppContainer.from(ApplicationProvider.getApplicationContext());
    }

    static boolean online() {
        Context context = ApplicationProvider.getApplicationContext();
        return context.getSystemService(ConnectivityManager.class).getActiveNetwork() != null;
    }

    /** Polls until the condition holds; returns whether it did within the timeout. */
    static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    static boolean awaitStatus(String modelId, Predicate<ModelStatus> wanted, long timeoutMs)
            throws InterruptedException {
        ModelRepository models = container().models;
        return await(() -> wanted.test(models.status(modelId)), timeoutMs);
    }

    /**
     * Downloads the model through the app's own path unless it is already installed. The
     * companion app is put on screen for the download, as it is when a user taps Download:
     * Android defers background work for apps that are not visible.
     */
    static void ensureInstalled(String modelId) throws InterruptedException {
        ModelRepository models = container().models;
        models.refreshFromDisk();
        if (models.isReady(modelId)) return;
        Assume.assumeTrue("needs network to install " + modelId, online());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            models.download(modelId);
            if (!awaitStatus(modelId, s -> s.state == AssetState.READY, 5 * 60_000)) {
                throw new AssertionError("model did not become ready: " + modelId + " state="
                        + models.status(modelId).state + " failure="
                        + models.status(modelId).failure);
            }
        }
    }

    /** The installed model's directory, installing it first if needed. */
    static File installedDir(String modelId) throws InterruptedException {
        ensureInstalled(modelId);
        return container().models.directory(ModelRegistry.find(modelId));
    }
}

// The app's download path end to end on a device: WorkManager worker, real weight files,
// the vendor SDK accepting them, a cancel that truly stops, and readiness tied to the files.
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.models.ModelDescriptor;
import com.example.aikeyboard.models.ModelDownloader;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ai.desertant.emo.Emo;
import ai.desertant.emo.EmoSuggestion;
import ai.desertant.emo.EmojiSkinTone;

@RunWith(AndroidJUnit4.class)
public class ModelDownloadDeviceTest {

    private Context context;
    private ModelRepository models;
    private ActivityScenario<MainActivity> companionApp;

    @Before
    public void setUp() {
        assumeTrue("needs network", TestModels.online());
        context = ApplicationProvider.getApplicationContext();
        models = TestModels.container().models;
        // Downloads are only ever started from the companion app while it is on screen.
        // Android defers background work for apps that are not, so the test does the same.
        companionApp = ActivityScenario.launch(MainActivity.class);
    }

    @After
    public void tearDown() {
        if (companionApp != null) companionApp.close();
    }

    private void removeCompletely(String modelId) throws InterruptedException {
        models.delete(modelId);
        assertTrue("files were not removed",
                TestModels.await(() -> models.bytesOnDisk(modelId) == 0, 10_000));
    }

    @Test
    public void aDownloadGoesThroughTheWorkerAndTheVendorSdkAcceptsTheFiles() throws Exception {
        ModelDescriptor emo = ModelRegistry.find("emo");
        removeCompletely("emo");
        assertFalse(models.isReady("emo"));

        models.download("emo");
        assertTrue("emo did not become ready: " + models.status("emo").state + " "
                        + models.status("emo").failure,
                TestModels.awaitStatus("emo", s -> s.state == AssetState.READY, 3 * 60_000));

        assertTrue(ModelDownloader.isComplete(models.directory(emo), emo.files));
        try (Emo sdk = new Emo(context, models.directory(emo).getAbsolutePath())) {
            assertTrue("the SDK must adopt files the app downloaded", sdk.isDownloaded());
            List<EmoSuggestion> suggestions = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> sdk.suggestions("Pizza sounds good", 3, EmojiSkinTone.DEFAULT, c))
                    .get(2, TimeUnit.MINUTES);
            assertFalse(suggestions.isEmpty());
        }
    }

    @Test
    public void cancellingReallyStopsTheTransferAndRemovesWhatItFetched() throws Exception {
        removeCompletely("toxic");
        models.download("toxic");
        assertTrue("toxic never started downloading: " + models.status("toxic").state,
                TestModels.awaitStatus("toxic",
                        s -> s.state == AssetState.DOWNLOADING && s.bytesDone > 1_000_000,
                        2 * 60_000));

        models.cancel("toxic");
        assertEquals(AssetState.NOT_INSTALLED, models.status("toxic").state);

        // Unlike the vendor's own download, nothing may keep arriving after a cancel.
        Thread.sleep(2000);
        long soonAfter = models.bytesOnDisk("toxic");
        Thread.sleep(2500);
        long later = models.bytesOnDisk("toxic");
        assertEquals("bytes kept arriving after cancel", soonAfter, later);
        assertEquals("partial files were left behind", 0, later);
        assertEquals(AssetState.NOT_INSTALLED, models.status("toxic").state);
    }

    @Test
    public void losingTheUnmeteredNetworkPausesTheDownloadAndItResumesWhenItReturns()
            throws Exception {
        UiDevice device = UiDevice.getInstance(getInstrumentation());
        ModelDescriptor toxic = ModelRegistry.find("toxic");
        TestModels.container().settings.setBoolean(AppSettings.UNMETERED_ONLY, true);
        removeCompletely("toxic");
        try {
            models.download("toxic");
            assertTrue("toxic never started downloading: " + models.status("toxic").state,
                    TestModels.awaitStatus("toxic",
                            s -> s.state == AssetState.DOWNLOADING && s.bytesDone > 1_000_000,
                            2 * 60_000));

            // Wi-Fi off leaves only the emulator's metered mobile network.
            device.executeShellCommand("svc wifi disable");
            assertTrue("the download did not pause: " + models.status("toxic").state,
                    TestModels.awaitStatus("toxic", s -> s.state == AssetState.QUEUED, 30_000));
            Thread.sleep(1500);
            long paused = models.bytesOnDisk("toxic");
            Thread.sleep(2500);
            assertEquals("bytes kept arriving over the metered network",
                    paused, models.bytesOnDisk("toxic"));
            assertTrue("the partial download should be kept for resuming", paused > 0);

            device.executeShellCommand("svc wifi enable");
            assertTrue("the download did not resume and finish: "
                            + models.status("toxic").state + " " + models.status("toxic").failure,
                    TestModels.awaitStatus("toxic", s -> s.state == AssetState.READY,
                            4 * 60_000));
            assertTrue(ModelDownloader.isComplete(models.directory(toxic), toxic.files));
        } finally {
            device.executeShellCommand("svc wifi enable");
        }
    }

    @Test
    public void readinessComesFromTheFiles_aDamagedInstallIsNotReadyAndCanBeRepaired()
            throws Exception {
        ModelDescriptor emo = ModelRegistry.find("emo");
        TestModels.ensureInstalled("emo");

        // Damage one file the way an interrupted write would.
        File meta = new File(models.directory(emo), "emo_meta.json");
        try (FileOutputStream out = new FileOutputStream(meta)) {
            out.write(new byte[10]);
        }
        models.refreshFromDisk();
        assertFalse("a truncated file must not count as installed", models.isReady("emo"));
        assertEquals(AssetState.NOT_INSTALLED, models.status("emo").state);

        models.download("emo");
        assertTrue(TestModels.awaitStatus("emo", s -> s.state == AssetState.READY, 3 * 60_000));
        assertTrue(ModelDownloader.isComplete(models.directory(emo), emo.files));
    }
}

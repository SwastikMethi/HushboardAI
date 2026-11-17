// Opt-in proof of the crash breaker with a model that really takes the process down on a
// given device (for example Clear on an emulator without a real GPU, or Voz on a device with
// too little memory). Two separate runs, because the first one is expected to die:
//   am instrument -e crashProbe run    -e crashModel clear ...   (the process is killed)
//   am instrument -e crashProbe verify -e crashModel clear ...   (the next start recovered)
// Never part of a default run.
package com.example.aikeyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.ai.adapters.ClearAdapter;
import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.media.Voice.AudioClip;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CrashRecoveryProbeTest {

    private static String argument(String name) {
        return InstrumentationRegistry.getArguments().getString(name, "");
    }

    @Test
    public void run_loadsTheModelAndIsExpectedToDieWhereItCannotRun() throws Exception {
        assumeTrue("crashRun".equals("crash" + capitalised(argument("crashProbe"))));
        String modelId = argument("crashModel");
        AppContainer app = TestModels.container();
        TestModels.ensureInstalled(modelId);
        app.settings.setModelEnabled(modelId, true);
        AudioClip second = new AudioClip(new float[16_000], 16_000);
        AppSettings settings = new AppSettings(1, Map.of());
        app.inference.submit(() -> "clear".equals(modelId)
                ? new ClearAdapter(app.runtime).run(second, settings)
                : app.voz.run(second, settings)).get(15, TimeUnit.MINUTES);
        // Reaching this line means the model runs on this device: nothing to recover from.
    }

    @Test
    public void verify_theNextStartSwitchedTheModelOffAndSaysWhy() throws Exception {
        assumeTrue("crashVerify".equals("crash" + capitalised(argument("crashProbe"))));
        String modelId = argument("crashModel");
        AppContainer app = TestModels.container();
        assertTrue("the model should be flagged as having stopped the app", TestModels.await(
                () -> app.settings.latest().bool(AppSettings.modelCrashedKey(modelId), false),
                5000));
        assertFalse("the model should have been switched off",
                app.settings.latest().modelEnabled(modelId));
        assertTrue("its files are still installed; only the switch changed",
                app.models.isReady(modelId));
    }

    private static String capitalised(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}

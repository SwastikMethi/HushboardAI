// A model that cannot fit in the device's memory is refused before anything is downloaded or
// loaded, instead of being left to take the keyboard down when it is first used.
package com.example.aikeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.models.ModelRepository;
import com.example.aikeyboard.models.ModelRepository.AssetState;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ModelMemoryGuardTest {

    private static final long GIB = 1L << 30;

    private ModelRepository repositoryOnADeviceWith(long ramBytes) {
        Context context = ApplicationProvider.getApplicationContext();
        AppContainer app = TestModels.container();
        ModelRepository models = new ModelRepository(context, app.settings, app.io, ramBytes);
        models.refreshFromDisk();
        return models;
    }

    @Test
    public void theSpeechModelIsRefusedOnADeviceThatCannotHoldIt() {
        ModelRepository models = repositoryOnADeviceWith(3 * GIB);

        assertEquals(AssetState.INCOMPATIBLE, models.status("voz").state);
        assertTrue(models.tooBigForDevice("voz"));
        assertFalse("it must never be loaded here, installed or not", models.isReady("voz"));

        models.download("voz");
        assertEquals("no download may start for it", AssetState.INCOMPATIBLE,
                models.status("voz").state);
    }

    @Test
    public void smallModelsAreUnaffectedAndABigDeviceGetsTheSpeechModel() {
        ModelRepository small = repositoryOnADeviceWith(3 * GIB);
        assertNotEquals(AssetState.INCOMPATIBLE, small.status("emo").state);
        assertFalse(small.tooBigForDevice("emo"));

        ModelRepository big = repositoryOnADeviceWith(8 * GIB);
        assertNotEquals(AssetState.INCOMPATIBLE, big.status("voz").state);
    }
}

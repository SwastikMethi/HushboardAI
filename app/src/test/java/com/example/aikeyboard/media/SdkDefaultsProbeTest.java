// The app's default settings for the voice models must be the vendor SDK's own defaults, so
// a setting the user has never touched changes nothing. Plain JVM: no model is loaded.
package com.example.aikeyboard.media;

import static org.junit.Assert.assertEquals;

import com.example.aikeyboard.models.ModelRegistry;

import org.junit.Test;

import java.util.Locale;

public class SdkDefaultsProbeTest {

    @Test
    public void cleanupStrengthStartsAtTheVendorsDefault() {
        ai.desertant.clear.Options vendor = new ai.desertant.clear.Options();
        assertEquals(ModelRegistry.CLEAR_STRENGTH_DEFAULT / 100.0, vendor.getStrength(), 1e-9);
    }

    @Test
    public void fillerBiasStartsAtTheVendorsDefault() {
        ai.desertant.uhm.Options vendor = new ai.desertant.uhm.Options();
        assertEquals(ModelRegistry.UHM_BIAS_DEFAULT,
                vendor.getBias().name().toLowerCase(Locale.ROOT));
    }
}

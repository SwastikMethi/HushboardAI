// Types into a field that belongs to another app (Android's own Settings search), to show the
// keyboard works outside this app's test screen. Opt-in, because it depends on what the
// device has installed: run with  -e stockApps true
package com.example.aikeyboard;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StockAppTypingTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 10000;

    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        assumeTrue("opt-in: pass -e stockApps true",
                "true".equals(InstrumentationRegistry.getArguments().getString("stockApps")));
        device = UiDevice.getInstance(getInstrumentation());
        device.executeShellCommand("ime enable " + IME_ID);
        device.executeShellCommand("ime set " + IME_ID);
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
    }

    @Test
    public void typesIntoAndroidSettingsSearch() {
        Context context = ApplicationProvider.getApplicationContext();
        context.startActivity(new Intent(Settings.ACTION_APP_SEARCH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

        // Android's own Settings uses a plain EditText; OnePlus's uses an AutoCompleteTextView.
        java.util.regex.Pattern textField = java.util.regex.Pattern.compile(
                "android\\.widget\\.(EditText|AutoCompleteTextView)");
        UiObject2 field = device.wait(Until.findObject(By.clazz(textField)), TIMEOUT_MS);
        assertNotNull("no search field in Settings", field);
        String hostPackage = field.getApplicationPackage();
        field.click();
        for (String label : new String[] {"w", "i", "f", "i"}) {
            UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
            assertNotNull("key not found: " + label, key);
            key.click();
        }
        device.waitForIdle();
        UiObject2 typed = device.wait(Until.findObject(
                By.pkg(hostPackage).clazz(textField).text("wifi")), TIMEOUT_MS);
        assertNotNull("the host app's field did not receive the text", typed);
        assertEquals("wifi", typed.getText());
        device.pressHome();
    }
}

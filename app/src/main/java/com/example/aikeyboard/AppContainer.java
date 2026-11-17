// The app's shared objects, created once per process and handed to whoever needs them. The
// keyboard service, the companion Activity and the download worker all run in this process
// and all use the same instances.
package com.example.aikeyboard;

import android.content.Context;

import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppContainer {

    public final SettingsRepository settings;
    public final ModelRepository models;
    /** File and network work. Never used for inference, which has its own single slot. */
    public final ExecutorService io = Executors.newFixedThreadPool(2);

    AppContainer(Context appContext) {
        settings = new SettingsRepository(appContext);
        models = new ModelRepository(appContext, settings, io);
    }

    public static AppContainer from(Context context) {
        return ((KeyboardApplication) context.getApplicationContext()).container();
    }
}

// Process entry point: builds the one AppContainer before any component starts.
package com.example.aikeyboard;

import android.app.Application;

public class KeyboardApplication extends Application {

    private AppContainer container;

    @Override
    public void onCreate() {
        super.onCreate();
        container = new AppContainer(this);
    }

    AppContainer container() {
        return container;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Typing must survive memory pressure; loaded models are the thing to give up.
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && level < TRIM_MEMORY_UI_HIDDEN
                || level >= TRIM_MEMORY_COMPLETE) {
            container.releaseAllModels();
        }
    }
}

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
}

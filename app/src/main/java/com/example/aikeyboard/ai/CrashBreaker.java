// Notices when a model killed the process while loading. All models share the keyboard's
// process, so without this a model that crashes on load would crash typing again and again.
package com.example.aikeyboard.ai;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class CrashBreaker {

    // One empty marker file per model that is in the middle of its first load.
    private final File dir;

    public CrashBreaker(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    /** Call just before a model's native load. Written synchronously so a crash cannot beat it. */
    public void loading(String modelId) {
        try {
            new File(dir, modelId).createNewFile();
        } catch (IOException e) {
            // Without a marker the breaker simply cannot help for this load.
        }
    }

    /** Call once the model has loaded and answered. */
    public void loaded(String modelId) {
        new File(dir, modelId).delete();
    }

    /**
     * Models whose load never finished in an earlier process. Each is reported once: the
     * markers are removed, and the caller switches those models off.
     */
    public Set<String> crashedLastTime() {
        Set<String> crashed = new HashSet<>();
        File[] markers = dir.listFiles();
        if (markers == null) return crashed;
        for (File marker : markers) {
            crashed.add(marker.getName());
            marker.delete();
        }
        return crashed;
    }
}

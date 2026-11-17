// Development probe, not a product path: lets the vendor SDK download one model so the file
// list, sizes and hashes it pins can be read and copied into ModelRegistry. Opt-in:
//   -e captureModel gist
// The manifest is printed to logcat under the tag "ManifestCapture".
package com.example.aikeyboard;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.sdkbridge.DesertAntClient;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

@RunWith(AndroidJUnit4.class)
public class ManifestCaptureTest {

    private static final String TAG = "ManifestCapture";

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    @Test
    public void captureWhatTheSdkDownloadsForOneModel() throws Exception {
        String id = InstrumentationRegistry.getArguments().getString("captureModel");
        assumeTrue("opt-in: pass -e captureModel <id>", id != null);
        assumeTrue("needs network", TestModels.online());
        Context context = ApplicationProvider.getApplicationContext();
        File dir = new File(context.getNoBackupFilesDir(), "manifest-probe/" + id);
        deleteRecursively(dir);
        dir.mkdirs();

        // Every vendor model class has the same shape: Name(Context, String) and download().
        String className = "ai.desertant." + id + "."
                + id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        Class<?> type = Class.forName(className);
        Object model = type.getConstructor(Context.class, String.class)
                .newInstance(context, dir.getAbsolutePath());
        Method download = type.getMethod("download", Continuation.class);
        try {
            long started = System.currentTimeMillis();
            DesertAntClient.<Unit>run(c -> {
                try {
                    return download.invoke(model, c);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }).get(20, TimeUnit.MINUTES);
            Log.i(TAG, id + " downloadMs=" + (System.currentTimeMillis() - started));

            File manifest = new File(dir, ".dal-meta/manifest");
            assertTrue("the SDK wrote no manifest", manifest.isFile());
            for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
                Log.i(TAG, id + " | " + line.replace('\t', ' '));
            }
        } finally {
            ((AutoCloseable) model).close();
            deleteRecursively(dir);
        }
    }
}

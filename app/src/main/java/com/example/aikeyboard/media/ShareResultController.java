// Hands a result to another app when the user asks to share it. The file is written to the
// app's private cache and offered through a content URI with a read grant for that one share;
// nothing is exported without a tap. Retention: shared files are deleted once they are over
// an hour old, checked each time something is shared and each time the app starts.
package com.example.aikeyboard.media;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.example.aikeyboard.media.Voice.AudioClip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ShareResultController {

    private static final long KEEP_MS = 60 * 60 * 1000L;

    private ShareResultController() {}

    /** Blocking (writes a file): call off the main thread. */
    public static Intent audioShareIntent(Context context, AudioClip clip) throws IOException {
        File dir = sharedDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache directory");
        removeOldFiles(context);
        File file = new File(dir, "voice-" + System.currentTimeMillis() + ".wav");
        Files.write(file.toPath(), AudioPipeline.toWav(clip));
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The grant travels with the clip data when the share sheet forwards the intent.
        share.setClipData(ClipData.newRawUri(null, uri));
        return share;
    }

    /** Deletes shared files that are past their keeping time. */
    public static void removeOldFiles(Context context) {
        File[] files = sharedDir(context).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (File file : files) {
            if (file.lastModified() < cutoff) file.delete();
        }
    }

    private static File sharedDir(Context context) {
        return new File(context.getCacheDir(), "shared");
    }
}

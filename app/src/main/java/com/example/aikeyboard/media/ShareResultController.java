// Hands a result to another app when the user asks to share or insert it. The file is
// written to the app's private cache and offered through a content URI with a read grant for
// that one use; nothing is exported without a tap. Retention: shared files are deleted once
// they are over an hour old, checked each time something is shared and each time the app
// starts.
package com.example.aikeyboard.media;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.example.aikeyboard.media.Voice.AudioClip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

public final class ShareResultController {

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    private static final long KEEP_MS = 60 * 60 * 1000L;

    private ShareResultController() {}

    /** Blocking (writes a file): call off the main thread. */
    public static Intent audioShareIntent(Context context, AudioClip clip) throws IOException {
        File file = newFile(context, "voice", ".wav");
        Files.write(file.toPath(), AudioPipeline.toWav(clip));
        return shareIntent(uriFor(context, file), "audio/wav");
    }

    /**
     * Writes a picture where another app can be handed it, and returns its content URI.
     * Blocking: call off the main thread.
     *
     * @param mimeType PNG for drawings (sharp edges), JPEG for photos (smaller)
     */
    public static Uri writePicture(Context context, Bitmap picture, String mimeType)
            throws IOException {
        boolean png = PNG.equals(mimeType);
        File file = newFile(context, "picture", png ? ".png" : ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            if (!picture.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    90, out)) {
                throw new IOException("the picture could not be written");
            }
        }
        return uriFor(context, file);
    }

    /** The share-sheet request for something already written with a method above. */
    public static Intent shareIntent(Uri uri, String mimeType) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
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

    private static File newFile(Context context, String prefix, String extension)
            throws IOException {
        File dir = sharedDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache directory");
        removeOldFiles(context);
        return new File(dir, prefix + "-" + System.currentTimeMillis() + extension);
    }

    private static Uri uriFor(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
    }

    private static File sharedDir(Context context) {
        return new File(context.getCacheDir(), "shared");
    }
}

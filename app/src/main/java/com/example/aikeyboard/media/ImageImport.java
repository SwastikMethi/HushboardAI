// Opens a picture the user picked and holds it in memory at a bounded size, so a very large
// photo cannot exhaust memory. Nothing is copied to disk.
package com.example.aikeyboard.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;

public final class ImageImport {

    /** The longest side, in pixels, a picture is held and analysed at. */
    public static final int MAX_SIDE = 1600;

    private ImageImport() {}

    /** How many times to halve a picture so that its longer side fits: 1, 2, 4, 8… */
    static int sampleSize(int width, int height, int maxSide) {
        int sample = 1;
        while (Math.max(width, height) / sample > maxSide) sample *= 2;
        return sample;
    }

    /**
     * Blocking; call off the main thread. Throws IOException when the file cannot be read as
     * a picture.
     */
    public static Bitmap decode(Context context, Uri uri, int maxSide) throws IOException {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Also turns the picture the right way up, following the camera's own note.
                bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.getContentResolver(), uri),
                        (decoder, info, source) -> {
                            // The models and the share step need ordinary in-memory pixels.
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            decoder.setTargetSampleSize(sampleSize(info.getSize().getWidth(),
                                    info.getSize().getHeight(), maxSide));
                        });
            } else {
                // ponytail: Android 8 only. The camera's rotation note is ignored here, so a
                // photo can come out sideways; read it with ExifInterface if that matters.
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, options);
                }
                options.inSampleSize = sampleSize(options.outWidth, options.outHeight, maxSide);
                options.inJustDecodeBounds = false;
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
            }
            if (bitmap == null) throw new IOException("not a picture");
            return bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap
                    : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException e) {
            // How the platform reports a file it cannot make sense of.
            throw new IOException("not a picture", e);
        }
    }
}

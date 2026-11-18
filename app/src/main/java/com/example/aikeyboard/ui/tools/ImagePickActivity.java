// Lets the user choose one picture for the keyboard. A keyboard service cannot open the
// system's picker or receive what was chosen, so this screen does. It shows nothing of its
// own: it opens the picker at once, reads the chosen picture while it still has the right
// to, hands it to the keyboard and closes. The app is given that one picture and no other.
package com.example.aikeyboard.ui.tools;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.aikeyboard.AppContainer;
import com.example.aikeyboard.R;
import com.example.aikeyboard.media.ImageImport;

import java.io.IOException;

public final class ImagePickActivity extends ComponentActivity {

    private final ActivityResultLauncher<PickVisualMediaRequest> picker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    this::onPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Not opened again after a rotation: the picker is already showing.
        if (savedInstanceState == null) {
            picker.launch(new PickVisualMediaRequest.Builder().setMediaType(
                    ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
        }
    }

    private void onPicked(Uri uri) {
        if (uri == null) { // the picker was closed without a choice
            finish();
            return;
        }
        AppContainer app = AppContainer.from(this);
        // Read before this screen closes: on some devices the right to read the picture
        // ends with the screen that was given it.
        app.io.execute(() -> {
            Bitmap picture = null;
            try {
                picture = ImageImport.decode(getApplicationContext(), uri, ImageImport.MAX_SIDE);
            } catch (IOException unreadable) {
                // Said below, on the main thread.
            }
            Bitmap read = picture;
            runOnUiThread(() -> {
                if (read != null) {
                    app.picturePicked.accept(read);
                } else {
                    Toast.makeText(this, R.string.image_unreadable, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        });
    }
}

// Asks for the microphone at the moment the user taps record. A keyboard service cannot show
// a permission prompt itself, so both the keyboard and the Voice tools screen come through
// here. Finishes with RESULT_OK once access is granted. If the user has refused for good, the
// only way back is the app's settings page, which this screen links to.
package com.example.aikeyboard.ui.tools;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.aikeyboard.R;
import com.example.aikeyboard.databinding.ActivityMicPermissionBinding;

public final class MicPermissionActivity extends AppCompatActivity {

    private ActivityMicPermissionBinding views;

    private final ActivityResultLauncher<String> request = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) finishGranted();
                else showRefused();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        views = ActivityMicPermissionBinding.inflate(getLayoutInflater());
        setContentView(views.getRoot());
        views.micClose.setOnClickListener(v -> finish());
        if (granted()) {
            finishGranted();
        } else if (savedInstanceState == null) {
            // Not asked again after a rotation: the system prompt is already showing.
            request.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the settings page with access switched on.
        if (granted()) finishGranted();
    }

    private boolean granted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void finishGranted() {
        setResult(RESULT_OK);
        finish();
    }

    private void showRefused() {
        views.micMessage.setText(R.string.mic_refused);
        views.micAction.setVisibility(View.VISIBLE);
        // The system only lets an app ask again while it still offers a rationale; after
        // that the prompt no longer appears and the settings page is the way to allow it.
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            views.micAction.setText(R.string.mic_ask_again);
            views.micAction.setOnClickListener(
                    v -> request.launch(Manifest.permission.RECORD_AUDIO));
        } else {
            views.micAction.setText(R.string.mic_open_settings);
            views.micAction.setOnClickListener(v -> startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null))));
        }
    }
}

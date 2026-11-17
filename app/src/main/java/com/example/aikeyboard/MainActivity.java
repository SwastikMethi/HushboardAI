// Companion app entry point. Hosts the Keyboard / Models / Settings screens (tabs land in M2).
package com.example.aikeyboard;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.aikeyboard.ui.onboarding.SetupFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The window is edge-to-edge (targetSdk 35+): keep content clear of the system bars,
        // any display cutout, and the keyboard when it is showing.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.screen), (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        // After a configuration change the fragment manager restores the screen itself.
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.screen, new SetupFragment())
                    .commit();
        }
    }
}

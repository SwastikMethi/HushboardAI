// Companion app entry point: three tabs (Keyboard, Models, Settings) and the screens opened
// from them.
package com.example.aikeyboard;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.aikeyboard.databinding.ActivityMainBinding;
import com.example.aikeyboard.ui.models.ModelDetailFragment;
import com.example.aikeyboard.ui.models.ModelLibraryFragment;
import com.example.aikeyboard.ui.onboarding.SetupFragment;
import com.example.aikeyboard.ui.settings.AppPreferencesFragment;
import com.example.aikeyboard.ui.settings.PrivacyFragment;
import com.example.aikeyboard.ui.settings.SettingsFragment;
import com.example.aikeyboard.ui.tools.VoiceToolsFragment;

public class MainActivity extends AppCompatActivity {

    /** Optional intent extra: which tab to open ("models" or "settings"). */
    public static final String EXTRA_TAB = "tab";
    public static final String TAB_MODELS = "models";
    public static final String TAB_SETTINGS = "settings";

    private ActivityMainBinding views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        views = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(views.getRoot());

        // The window is edge-to-edge (targetSdk 35+): keep the screen clear of the status
        // bar and any cutout, and lift it above the keyboard while one is showing. The tab
        // bar pads itself for the navigation area.
        ViewCompat.setOnApplyWindowInsetsListener(views.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, keyboard.bottom);
            return insets;
        });

        views.tabs.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        // After a configuration change the fragment manager restores the screen itself.
        if (savedInstanceState == null) {
            showTab(R.id.tab_keyboard);
            openRequestedTab(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openRequestedTab(intent);
    }

    private void openRequestedTab(Intent intent) {
        String tab = intent.getStringExtra(EXTRA_TAB);
        if (TAB_MODELS.equals(tab)) selectTab(R.id.tab_models);
        else if (TAB_SETTINGS.equals(tab)) selectTab(R.id.tab_settings);
    }

    /** Switches tab as if the user had tapped it. */
    public void selectTab(int tabId) {
        views.tabs.setSelectedItemId(tabId);
    }

    private void showTab(int tabId) {
        Fragment screen;
        // R ids are not compile-time constants under AGP 9, so this cannot be a switch.
        if (tabId == R.id.tab_models) screen = new ModelLibraryFragment();
        else if (tabId == R.id.tab_settings) screen = new SettingsFragment();
        else screen = new SetupFragment();
        FragmentManager fragments = getSupportFragmentManager();
        fragments.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fragments.beginTransaction().replace(R.id.screen, screen).commit();
    }

    public void openModelDetail(String modelId) {
        open(ModelDetailFragment.forModel(modelId));
    }

    public void openPrivacy() {
        open(new PrivacyFragment());
    }

    public void openAppPreferences() {
        open(new AppPreferencesFragment());
    }

    public void openVoiceTools() {
        open(new VoiceToolsFragment());
    }

    // A screen reached from a tab: Back returns to the tab it came from.
    private void open(Fragment screen) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.screen, screen)
                .addToBackStack(null)
                .commit();
    }
}

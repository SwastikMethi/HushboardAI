// The Image tools screen with the real Eye and Moderator models: how it is reached, what it
// shows for a picked picture, a cancelled pick, a file that is not a picture, a very large
// picture, and the warning before a flagged picture is shared. No explicit imagery is used:
// the flagged path is reached by lowering Moderator's own threshold to zero.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;
import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.ui.tools.ImageToolsFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class ImageToolsScreenTest {

    private static final String PKG = "com.example.aikeyboard";
    private static final long RUN_MS = 60_000;
    private static final String THRESHOLD =
            AppSettings.modelSettingKey("moderator", ModelRegistry.MODERATOR_THRESHOLD);

    private final SettingsRepository settings = TestModels.container().settings;
    private ActivityScenario<MainActivity> app;
    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        TestModels.ensureInstalled("eye");
        TestModels.ensureInstalled("moderator");
        device = UiDevice.getInstance(getInstrumentation());
        settings.setInt(THRESHOLD, ModelRegistry.MODERATOR_THRESHOLD_DEFAULT);
        settings.setBoolean(AppSettings.AI_ENABLED, true);
        use(true, "eye", "moderator");
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled(), 5000));
        app = ActivityScenario.launch(MainActivity.class);
        app.onActivity(MainActivity::openImageTools);
        getInstrumentation().waitForIdleSync();
    }

    @After
    public void tearDown() {
        if (app != null) app.close();
        settings.setInt(THRESHOLD, ModelRegistry.MODERATOR_THRESHOLD_DEFAULT);
        for (String id : new String[] {"eye", "moderator"}) settings.setModelEnabled(id, false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    private void use(boolean on, String... modelIds) throws InterruptedException {
        for (String id : modelIds) settings.setModelEnabled(id, on);
        String last = modelIds[modelIds.length - 1];
        assertTrue(TestModels.await(() -> settings.latest().modelEnabled(last) == on, 5000));
        getInstrumentation().waitForIdleSync();
    }

    private String shown(int viewId) {
        String[] text = {""};
        app.onActivity(activity -> {
            TextView view = activity.findViewById(viewId);
            if (view != null && view.isShown()) text[0] = view.getText().toString();
        });
        return text[0];
    }

    private File pictureFile(String name, Bitmap bitmap, Bitmap.CompressFormat format)
            throws Exception {
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(format, 90, out);
        }
        return file;
    }

    private File harmless() throws Exception {
        return pictureFile("house.png", VisionModelsTest.harmlessPicture(),
                Bitmap.CompressFormat.PNG);
    }

    private void pick(Uri uri) {
        app.onActivity(activity -> ((ImageToolsFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.screen)).onPicturePicked(uri));
    }

    private void awaitResults() throws InterruptedException {
        assertTrue("no result appeared; status: " + shown(R.id.image_status),
                TestModels.await(() -> !shown(R.id.image_sensitivity).isEmpty(), RUN_MS));
    }

    private int[] previewSize() {
        int[] size = new int[2];
        app.onActivity(activity -> {
            ImageView preview = activity.findViewById(R.id.image_preview);
            if (preview.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap held = ((android.graphics.drawable.BitmapDrawable)
                        preview.getDrawable()).getBitmap();
                size[0] = held.getWidth();
                size[1] = held.getHeight();
            }
        });
        return size;
    }

    @Test
    public void theModelsTabLeadsToImageTools() {
        pressBack();
        closeSoftKeyboard();
        onView(withId(R.id.tab_models)).perform(click());
        onView(withText(R.string.open_image_tools)).perform(scrollTo(), click());

        onView(withId(R.id.image_steps)).check(matches(isDisplayed()));
    }

    @Test
    public void theScreenSaysWhichChecksWillRunAndSaysSoWhenNoneCan() throws Exception {
        String steps = shown(R.id.image_steps);
        assertTrue(steps, steps.contains("Eye") && steps.contains("Moderator"));
        onView(withId(R.id.image_choose)).check(matches(isEnabled()));

        use(false, "eye", "moderator");

        onView(withId(R.id.image_steps)).check(matches(withText(R.string.image_no_steps)));
        onView(withId(R.id.image_choose)).check(matches(not(isEnabled())));
    }

    @Test
    public void aPickedPictureIsShownWithWhatTheModelsFound() throws Exception {
        pick(Uri.fromFile(harmless()));
        awaitResults();

        assertEquals("the picture itself is shown", 640, previewSize()[0]);
        assertFalse("Eye says what sort of picture it is", shown(R.id.image_kind).isEmpty());
        assertEquals(getInstrumentation().getTargetContext().getString(
                R.string.image_not_flagged), shown(R.id.image_sensitivity));
    }

    @Test
    public void cancellingThePickerChangesNothing() throws Exception {
        pick(Uri.fromFile(harmless()));
        awaitResults();
        String before = shown(R.id.image_kind);

        pick(null); // what the system picker reports when it is closed without a choice

        getInstrumentation().waitForIdleSync();
        assertEquals(before, shown(R.id.image_kind));
        assertEquals(640, previewSize()[0]);
    }

    @Test
    public void aFileThatIsNotAPictureIsRefusedWithAReason() throws Exception {
        File text = new File(getInstrumentation().getTargetContext().getCacheDir(), "notes.txt");
        Files.write(text.toPath(), "not a picture".getBytes(StandardCharsets.UTF_8));

        pick(Uri.fromFile(text));

        assertTrue("status: " + shown(R.id.image_status), TestModels.await(
                () -> shown(R.id.image_status).contains("picture"), 20_000));
        assertEquals("nothing was analysed", "", shown(R.id.image_sensitivity));
    }

    @Test
    public void aVeryLargePictureIsScaledDownAndStillAnalysed() throws Exception {
        Bitmap large = Bitmap.createBitmap(6000, 4500, Bitmap.Config.RGB_565);
        new Canvas(large).drawColor(Color.rgb(90, 140, 200));
        File file = pictureFile("large.jpg", large, Bitmap.CompressFormat.JPEG);
        large.recycle();

        pick(Uri.fromFile(file));
        awaitResults();

        int[] size = previewSize();
        assertTrue("held at " + size[0] + "x" + size[1], size[0] <= 1600 && size[0] >= 750);
    }

    @Test
    public void aPictureThatIsNotFlaggedIsSharedStraightAway() throws Exception {
        pick(Uri.fromFile(harmless()));
        awaitResults();

        onView(withId(R.id.image_share)).perform(scrollTo(), click());

        assertTrue("the system share sheet did not open",
                device.wait(Until.gone(By.res(PKG, "image_share")), 10_000));
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "image_share")), 10_000));
    }

    @Test
    public void aFlaggedPictureAsksBeforeItIsShared() throws Exception {
        // Zero is the model's own threshold at its strictest: every picture is flagged.
        settings.setInt(THRESHOLD, 0);
        assertTrue(TestModels.await(() -> settings.latest().integer(THRESHOLD, -1) == 0, 5000));
        pick(Uri.fromFile(harmless()));
        awaitResults();
        assertTrue(shown(R.id.image_sensitivity), shown(R.id.image_sensitivity)
                .startsWith(getInstrumentation().getTargetContext()
                        .getString(R.string.image_flagged).substring(0, 20)));

        onView(withId(R.id.image_share)).perform(scrollTo(), click());

        // A warning first; sharing is still the user's call.
        onView(withText(R.string.image_share_warning)).inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText(R.string.action_cancel)).inRoot(isDialog()).perform(click());
        assertTrue("declining must not share",
                device.wait(Until.hasObject(By.res(PKG, "image_share")), 5000));

        onView(withId(R.id.image_share)).perform(scrollTo(), click());
        onView(withText(R.string.image_share_anyway)).inRoot(isDialog()).perform(click());
        assertTrue("the system share sheet did not open",
                device.wait(Until.gone(By.res(PKG, "image_share")), 10_000));
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "image_share")), 10_000));
    }
}

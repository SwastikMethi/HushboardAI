// Measurements for a named device, run only on request (-e latency true): how long a key
// press takes to reach the text, how long after the last key an emoji suggestion appears,
// how long the main thread is ever held up while models load and run, and how long each
// typing model takes. It reports numbers and asserts only that the measuring itself worked.
// It runs no shell command: the keyboard must already be selected on the device.
package com.example.aikeyboard;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Root;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.example.aikeyboard.data.AppSettings;
import com.example.aikeyboard.data.SettingsRepository;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class LatencyProbeTest {

    private static final String TAG = "Latency";
    private static final String PKG = "com.example.aikeyboard";
    private static final String IME_ID = PKG + "/.ime.AiInputMethodService";
    private static final long TIMEOUT_MS = 8000;
    private static final String SENTENCE = "the quick brown fox jumps over the lazy dog ";
    // Words the emoji model has something to say about.
    private static final String[] WORDS = {"pizza", "birthday", "coffee", "beach", "party",
            "happy", "football", "music", "rain", "love"};
    private static final String[] TYPING_MODELS = {"emo", "redact", "toxic", "tongue"};

    private final AppContainer app = TestModels.container();
    private final SettingsRepository settings = app.settings;
    private final UiDevice device = UiDevice.getInstance(getInstrumentation());
    private final UiAutomation automation = getInstrumentation().getUiAutomation();
    private final Map<Character, Point> keys = new HashMap<>();
    private ActivityScenario<EditorTestActivity> scenario;
    private EditText field;

    // Written on the main thread by the two watchers, read by the test thread.
    private volatile long textChangedAt;
    private volatile CountDownLatch textChanged = new CountDownLatch(1);
    private volatile long emojiShownAt;
    private volatile CountDownLatch emojiShown = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        assumeTrue("measurement only: pass -e latency true", "true".equals(
                InstrumentationRegistry.getArguments().getString("latency")));
        assumeTrue("select AI Keyboard as the keyboard on this device first", IME_ID.equals(
                Settings.Secure.getString(getInstrumentation().getTargetContext()
                        .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)));
        for (String id : new String[] {"emo", "redact", "toxic", "gist"}) {
            TestModels.ensureInstalled(id);
        }
        for (String id : TYPING_MODELS) settings.setModelEnabled(id, true);
        setAi(false);

        scenario = ActivityScenario.launch(EditorTestActivity.class);
        device.wait(Until.findObject(By.res(PKG, "field_plain")), TIMEOUT_MS).click();
        assertNotNull("keyboard did not appear",
                device.wait(Until.findObject(By.res(PKG, "keys")), TIMEOUT_MS));
        for (char letter = 'a'; letter <= 'z'; letter++) {
            keys.put(letter, centreOf(String.valueOf(letter)));
        }
        keys.put(' ', centreOf("Space"));
        scenario.onActivity(activity -> {
            field = activity.findViewById(R.id.field_plain);
            field.addTextChangedListener(new Watcher(() -> {
                textChangedAt = SystemClock.uptimeMillis();
                textChanged.countDown();
            }));
        });
    }

    @After
    public void tearDown() {
        if (scenario == null) return; // skipped before anything was set up
        scenario.close();
        for (String id : TYPING_MODELS) settings.setModelEnabled(id, false);
        settings.setBoolean(AppSettings.AI_ENABLED, false);
    }

    // ---- Measurements ----

    @Test
    public void aKeyPressReachesTheText() throws Exception {
        report("key_to_text.ai_off", typeSentence(200));

        setAi(true);
        typeSentence(40); // first use loads the models; not part of the steady figure
        Thread.sleep(2000);
        Heartbeat heartbeat = new Heartbeat();
        report("key_to_text.ai_on", typeSentence(200));
        report("main_thread_worst_hold.typing_ai_on", List.of(heartbeat.stop()));
    }

    @Test
    public void anEmojiFollowsTheLastKey() throws Exception {
        setAi(true);
        watchTheStrip();

        // Cold: every model has to be loaded before the first suggestion can appear.
        app.releaseAllModels();
        Thread.sleep(1000);
        Heartbeat heartbeat = new Heartbeat();
        report("last_key_to_emoji.models_not_loaded", List.of(emojiAfter(WORDS[0])));
        report("main_thread_worst_hold.while_models_load", List.of(heartbeat.stop()));

        List<Long> warm = new ArrayList<>();
        for (int i = 1; i <= 30; i++) warm.add(emojiAfter(WORDS[i % WORDS.length]));
        report("last_key_to_emoji.models_loaded", warm);
    }

    @Test
    public void eachTypingModelAnswers() throws Exception {
        String shortDraft = "Email me at anna@example.hu about the pizza party tonight";
        StringBuilder longDraft = new StringBuilder();
        while (longDraft.length() < 900) longDraft.append(shortDraft).append(". ");
        AppSettings defaults = new AppSettings(1, Map.of());
        for (String draft : new String[] {shortDraft, longDraft.toString()}) {
            String size = draft.length() + "_chars";
            report("model.emo." + size, timed(() -> app.emo.run(draft, defaults)));
            report("model.redact." + size, timed(() -> app.redact.run(draft, defaults)));
            report("model.toxic." + size, timed(() -> app.toxic.run(draft, defaults)));
            report("model.gist." + size, timed(() -> app.gist.run(draft, defaults)));
            report("model.tongue." + size, timed(() -> app.tongue.run(draft, defaults)));
        }
    }

    // ---- How each number is taken ----

    private void setAi(boolean on) throws InterruptedException {
        settings.setBoolean(AppSettings.AI_ENABLED, on);
        assertTrue(TestModels.await(() -> settings.latest().aiEnabled() == on, 5000));
    }

    private Point centreOf(String label) {
        UiObject2 key = device.wait(Until.findObject(By.pkg(PKG).desc(label)), TIMEOUT_MS);
        assertNotNull("key not found: " + label, key);
        return key.getVisibleCenter();
    }

    private void clearField() throws InterruptedException {
        scenario.onActivity(activity -> field.setText(""));
        Thread.sleep(600); // lets the strip empty and anything pending finish
    }

    /** Milliseconds from the finger leaving the key to the character being in the field. */
    private long press(char key) throws Exception {
        Point at = keys.get(key);
        textChanged = new CountDownLatch(1);
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down, down, at);
        Thread.sleep(30); // a quick tap
        long up = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_UP, down, up, at);
        assertTrue("the key press never reached the field: '" + key + "'",
                textChanged.await(3, TimeUnit.SECONDS));
        return textChangedAt - up;
    }

    private void touch(int action, long downTime, long eventTime, Point at) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, at.x, at.y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // Not waited for: the wait is what is being measured.
        automation.injectInputEvent(event, false);
        event.recycle();
    }

    private List<Long> typeSentence(int keyCount) throws Exception {
        clearField();
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            samples.add(press(SENTENCE.charAt(i % SENTENCE.length())));
            Thread.sleep(40); // about ten keys a second with the tap itself
        }
        return samples;
    }

    // The strip lives in the keyboard's own window, which is in this process.
    private void watchTheStrip() {
        onView(withId(R.id.suggestion_1)).inRoot(keyboardWindow()).check((view, missing) -> {
            if (missing != null) throw missing;
            ((TextView) view).addTextChangedListener(new Watcher(() -> {
                CharSequence shown = ((TextView) view).getText();
                if (shown.length() > 0 && Character.codePointAt(shown, 0) > 0x2000) {
                    emojiShownAt = SystemClock.uptimeMillis();
                    emojiShown.countDown();
                }
            }));
        });
    }

    private static Matcher<Root> keyboardWindow() {
        return new TypeSafeMatcher<Root>() {
            @Override
            protected boolean matchesSafely(Root root) {
                return root.getWindowLayoutParams().get().type
                        == WindowManager.LayoutParams.TYPE_INPUT_METHOD;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("the keyboard's window");
            }
        };
    }

    /** Milliseconds from the space after a word reaching the field to an emoji being shown. */
    private long emojiAfter(String word) throws Exception {
        clearField();
        for (char letter : word.toCharArray()) {
            press(letter);
            Thread.sleep(40);
        }
        emojiShown = new CountDownLatch(1);
        press(' ');
        long lastKey = textChangedAt;
        assertTrue("no emoji appeared after '" + word + "'",
                emojiShown.await(20, TimeUnit.SECONDS));
        return emojiShownAt - lastKey;
    }

    private List<Long> timed(Callable<?> run) throws Exception {
        return app.inference.submit(() -> {
            for (int i = 0; i < 3; i++) run.call();
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                long start = SystemClock.elapsedRealtimeNanos();
                run.call();
                samples.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000);
            }
            return samples;
        }).get(5, TimeUnit.MINUTES);
    }

    private void report(String name, List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int count = sorted.size();
        String line = "n=" + count + " p50=" + sorted.get(count / 2) + "ms p95="
                + sorted.get((int) Math.ceil(count * 0.95) - 1) + "ms max="
                + sorted.get(count - 1) + "ms";
        Log.i(TAG, name + ": " + line);
        Bundle status = new Bundle();
        status.putString("latency." + name, line);
        status.putString("latency.device", Build.MANUFACTURER + " " + Build.MODEL + " (Android "
                + Build.VERSION.RELEASE + ")");
        getInstrumentation().sendStatus(0, status);
    }

    private static final class Watcher implements TextWatcher {
        private final Runnable changed;

        Watcher(Runnable changed) {
            this.changed = changed;
        }

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) { }

        @Override
        public void afterTextChanged(Editable text) {
            changed.run();
        }
    }

    // Asks the main thread to run something every few milliseconds and remembers the longest
    // it was ever kept waiting: how long a key press could have gone unanswered.
    private static final class Heartbeat implements Runnable {
        private static final long EVERY_MS = 4;
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile long last = SystemClock.uptimeMillis();
        private volatile long worst;
        private volatile boolean running = true;

        Heartbeat() {
            main.postDelayed(this, EVERY_MS);
        }

        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            worst = Math.max(worst, now - last - EVERY_MS);
            last = now;
            if (running) main.postDelayed(this, EVERY_MS);
        }

        long stop() {
            running = false;
            return worst;
        }
    }
}

// Proves the real Desert Ant SDK from Java on a device: download, readiness, inference,
// offsets, cancellation and offline use. Sample text here is fixed test data, never a user draft.
package com.example.aikeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.aikeyboard.models.ModelRegistry;
import com.example.aikeyboard.sdkbridge.DesertAntClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import ai.desertant.emo.Emo;
import ai.desertant.emo.EmoSuggestion;
import ai.desertant.emo.EmojiSkinTone;
import ai.desertant.gist.Gist;
import ai.desertant.gist.Topic;
import ai.desertant.redact.Redact;
import ai.desertant.redact.Redaction;
import ai.desertant.redact.RedactionItem;
import ai.desertant.tongue.Detection;
import ai.desertant.tongue.Tongue;
import ai.desertant.toxic.Analysis;
import ai.desertant.toxic.Toxic;
import kotlin.Unit;

@RunWith(AndroidJUnit4.class)
public class ModelSmokeTest {

    private static final String TAG = "ModelSmoke";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    private File modelDir(String id) {
        File dir = new File(context.getNoBackupFilesDir(), "models/" + id);
        dir.mkdirs();
        return dir;
    }

    private static long logFiles(String label, File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                total += logFiles(label + "/" + f.getName(), f);
            } else {
                total += f.length();
                Log.i(TAG, "file " + label + "/" + f.getName() + " bytes=" + f.length());
            }
        }
        return total;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    // The kill probe needs an adb force-stop between its two steps, so it only runs on request.
    private static boolean killProbeRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("killProbe"));
    }

    private static boolean online(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        return cm.getActiveNetwork() != null;
    }

    @Test
    public void emo_runsFromTheAppsOwnDownloadAndSuggestsEmoji() throws Exception {
        File dir = TestModels.installedDir("emo");
        try (Emo emo = new Emo(context, dir.getAbsolutePath())) {
            assertTrue("the SDK must accept the files the app downloaded", emo.isDownloaded());
            Log.i(TAG, "emo total bytes=" + logFiles("emo", dir));

            long t0 = SystemClock.elapsedRealtime();
            List<EmoSuggestion> cold = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> emo.suggestions("Pizza sounds good", 3, EmojiSkinTone.DEFAULT, c))
                    .get(2, TimeUnit.MINUTES);
            long t1 = SystemClock.elapsedRealtime();
            List<EmoSuggestion> warm = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> emo.suggestions("Happy birthday my friend", 3, EmojiSkinTone.DEFAULT, c))
                    .get(1, TimeUnit.MINUTES);
            long t2 = SystemClock.elapsedRealtime();

            assertFalse(cold.isEmpty());
            assertTrue(cold.size() <= 3);
            assertFalse(warm.isEmpty());
            StringBuilder sb = new StringBuilder();
            for (EmoSuggestion s : cold) sb.append(s.getEmoji()).append('(').append(s.getConfidence()).append(") ");
            Log.i(TAG, "emo coldMs=" + (t1 - t0) + " warmMs=" + (t2 - t1) + " cold=" + sb);

            // Cancelling an inference must not poison the model for the next call.
            CompletableFuture<List<EmoSuggestion>> cancelled = DesertAntClient.run(
                    c -> emo.suggestions("See you tomorrow", 3, EmojiSkinTone.DEFAULT, c));
            cancelled.cancel(true);
            assertTrue(cancelled.isCancelled());
            List<EmoSuggestion> after = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> emo.suggestions("Good night", 3, EmojiSkinTone.DEFAULT, c))
                    .get(1, TimeUnit.MINUTES);
            assertFalse(after.isEmpty());
        }
    }

    @Test
    public void redact_offsetsAreUtf16CodeUnits() throws Exception {
        // A family emoji (ZWJ sequence), a combining diaeresis and a surrogate-pair emoji sit
        // before and after the spans, so any non-UTF-16 offset unit would shift them.
        String text = "👨‍👩‍👧 Zoë, email "
                + "anna@example.hu or call +36 30 123 4567 😀 thanks";
        File dir = TestModels.installedDir("redact");
        try (Redact redact = new Redact(context, dir.getAbsolutePath())) {
            assertTrue("the SDK must accept the files the app downloaded",
                    redact.isDownloaded());
            Log.i(TAG, "redact total bytes=" + logFiles("redact", dir));

            long t0 = SystemClock.elapsedRealtime();
            Redaction result = DesertAntClient.<Redaction>run(
                    c -> redact.redaction(text, new ai.desertant.redact.Options(), c))
                    .get(2, TimeUnit.MINUTES);
            Log.i(TAG, "redact coldMs=" + (SystemClock.elapsedRealtime() - t0)
                    + " redacted=" + result.getRedactedText());

            assertFalse(result.getItems().isEmpty());
            boolean sawEmail = false;
            for (RedactionItem item : result.getItems()) {
                Log.i(TAG, "redact item " + item.getLabel() + " [" + item.getStart() + ","
                        + item.getEnd() + ") conf=" + item.getConfidence()
                        + " placeholder=" + item.getPlaceholder());
                assertEquals(item.getOriginal(), text.substring(item.getStart(), item.getEnd()));
                if ("EMAIL".equals(item.getLabel())) {
                    sawEmail = true;
                    assertEquals("anna@example.hu", item.getOriginal());
                }
            }
            assertTrue("expected an EMAIL span", sawEmail);
        }
    }

    @Test
    public void toxic_scoresAThreat() throws Exception {
        File dir = TestModels.installedDir("toxic");
        try (Toxic toxic = new Toxic(context, dir.getAbsolutePath())) {
            assertTrue("the SDK must accept the files the app downloaded", toxic.isDownloaded());
            Log.i(TAG, "toxic total bytes=" + logFiles("toxic", dir));

            long t0 = SystemClock.elapsedRealtime();
            Analysis threat = DesertAntClient.<Analysis>run(c -> toxic.analyze(
                    "I will find you and hurt you, you worthless idiot",
                    new ai.desertant.toxic.Options(), c)).get(2, TimeUnit.MINUTES);
            long t1 = SystemClock.elapsedRealtime();
            Analysis benign = DesertAntClient.<Analysis>run(c -> toxic.analyze(
                    "Thanks for dinner, see you on Friday",
                    new ai.desertant.toxic.Options(), c)).get(1, TimeUnit.MINUTES);
            Log.i(TAG, "toxic coldMs=" + (t1 - t0) + " warmMs=" + (SystemClock.elapsedRealtime() - t1)
                    + " thresholds=" + threat.getThresholds()
                    + " threatScores=" + threat.getScores() + " flagged=" + threat.getFlagged()
                    + " benignFlagged=" + benign.getFlagged());

            assertFalse(threat.getScores().isEmpty());
            assertTrue("a plain threat should be flagged", threat.isToxic());
            assertFalse("a friendly message should not be flagged", benign.isToxic());
        }
    }

    @Test
    public void gist_tagsAMessageFromItsFixedListAndRespectsTheLimit() throws Exception {
        File dir = TestModels.installedDir("gist");
        try (Gist gist = new Gist(context, dir.getAbsolutePath())) {
            assertTrue("the SDK must accept the files the app downloaded", gist.isDownloaded());
            String text = "We had pizza and pasta for dinner at the new Italian restaurant";
            long t0 = SystemClock.elapsedRealtime();
            List<Topic> three = DesertAntClient.<List<Topic>>run(
                    c -> gist.classify(text, 3, null, c)).get(2, TimeUnit.MINUTES);
            long t1 = SystemClock.elapsedRealtime();
            List<Topic> one = DesertAntClient.<List<Topic>>run(
                    c -> gist.classify(text, 1, null, c)).get(1, TimeUnit.MINUTES);
            StringBuilder sb = new StringBuilder();
            for (Topic t : three) {
                sb.append(t.getSlug()).append('/').append(t.getName()).append('(')
                        .append(t.getScore()).append(") ");
            }
            Log.i(TAG, "gist coldMs=" + (t1 - t0) + " warmMs="
                    + (SystemClock.elapsedRealtime() - t1) + " topics=" + sb);
            assertFalse("a clear food message should get a topic", three.isEmpty());
            assertTrue(three.size() <= 3);
            assertTrue("the second argument is the number of topics", one.size() <= 1);
        }
    }

    @Test
    public void tongue_isBundledAndSynchronous() {
        Detection d = Tongue.bundled(context)
                .detect("Bonjour tout le monde, comment allez-vous aujourd'hui ?", 3);
        Log.i(TAG, "tongue language=" + d.getLanguage() + " reliability=" + d.getReliability()
                + " tooClose=" + d.isTooCloseToCall());
        assertTrue(d.getLanguage().startsWith("fr"));
    }

    /**
     * Characterises a vendor limitation found in the spike: cancelling the future releases the
     * caller, but the native transfer cannot be interrupted and runs to completion while holding
     * the model's lock. This is why the app downloads the pinned files itself. If a later SDK
     * makes download() cancellable, the final assertion fails and that design can be simplified.
     */
    @Test
    public void sdkDownload_cancelReleasesCallerButTransferRunsOn() throws Exception {
        assumeTrue("needs network", online(context));
        File dir = modelDir("cancel-probe");
        deleteRecursively(dir);
        dir.mkdirs();
        try (Toxic toxic = new Toxic(context, dir.getAbsolutePath())) {
            CompletableFuture<Unit> download = DesertAntClient.run(toxic::download);
            Thread.sleep(1500);
            assertTrue(download.cancel(true));
            assertTrue(download.isCancelled());

            Thread.sleep(2000);
            long afterCancel = logFiles("cancel-probe@2s", dir);
            Thread.sleep(3000);
            long later = logFiles("cancel-probe@5s", dir);
            Log.i(TAG, "cancel bytesAt2s=" + afterCancel + " bytesAt5s=" + later
                    + " stillGrowing=" + (later > afterCancel));

            long t = SystemClock.elapsedRealtime();
            boolean ready = toxic.isDownloaded(); // blocks until the native transfer lets go
            Log.i(TAG, "isDownloaded-after-cancel=" + ready + " blockedMs="
                    + (SystemClock.elapsedRealtime() - t));
            assertTrue("the transfer the caller cancelled still completed", ready);
        } finally {
            deleteRecursively(dir);
        }
    }

    /** The public file URL pattern serves exactly the bytes the SDK's own manifest pins. */
    @Test
    public void huggingFaceUrl_servesThePinnedFile() throws Exception {
        assumeTrue("needs network", online(context));
        URL url = new URL("https://huggingface.co/desert-ant-labs/emo/resolve/v0.7.0/emo_meta.json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestProperty("Range", "bytes=0-");
            int code = conn.getResponseCode();
            Log.i(TAG, "hf code=" + code + " finalHost=" + conn.getURL().getHost()
                    + " contentRange=" + conn.getHeaderField("Content-Range")
                    + " acceptRanges=" + conn.getHeaderField("Accept-Ranges"));
            assertTrue("expected 200 or 206, got " + code, code == 200 || code == 206);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sha.update(buf, 0, n);
                    total += n;
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b));
            assertEquals(6280, total);
            assertEquals("a3143258ac4099de65367cd4598073f1f5ce94f0eb505a30331762619967d3f6",
                    hex.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** Step 1 of the process-death probe: start a large SDK download, to be killed from adb. */
    @Test
    public void killProbe_startDownload() throws Exception {
        assumeTrue("manual two-step probe: pass -e killProbe true", killProbeRequested());
        assumeTrue("needs network", online(context));
        File dir = modelDir("kill-probe");
        deleteRecursively(dir);
        dir.mkdirs();
        Toxic toxic = new Toxic(context, dir.getAbsolutePath());
        Log.i(TAG, "kill-probe started");
        DesertAntClient.run(toxic::download).get(15, TimeUnit.MINUTES);
    }

    /** Step 2: after the kill, the half-finished model must not report ready. */
    @Test
    public void killProbe_afterKillIsNotReadyAndRetryCompletes() throws Exception {
        assumeTrue("manual two-step probe: pass -e killProbe true", killProbeRequested());
        File dir = modelDir("kill-probe");
        assumeTrue("run killProbe_startDownload and kill it first", dir.exists());
        try (Toxic toxic = new Toxic(context, dir.getAbsolutePath())) {
            long leftover = logFiles("kill-probe@restart", dir);
            assertFalse("a killed download must not report ready", toxic.isDownloaded());
            assumeTrue("needs network to retry", online(context));
            CompletableFuture<Unit> retry = DesertAntClient.run(toxic::download);
            Thread.sleep(700);
            long soonAfter = logFiles("kill-probe@retry+700ms", dir);
            Log.i(TAG, "kill-probe leftoverBytes=" + leftover + " bytes700msIntoRetry=" + soonAfter
                    + " resumed=" + (soonAfter >= leftover && leftover > 0));
            retry.get(15, TimeUnit.MINUTES);
            assertTrue(toxic.isDownloaded());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void offline_installedModelsRunAndMissingModelFailsVisibly() throws Exception {
        assumeTrue("run with the network off", !online(context));

        File installed = TestModels.container().models.directory(ModelRegistry.find("emo"));
        try (Emo emo = new Emo(context, installed.getAbsolutePath())) {
            assumeTrue("emo must already be downloaded", emo.isDownloaded());
            List<EmoSuggestion> s = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> emo.suggestions("Coffee tomorrow morning?", 3, EmojiSkinTone.DEFAULT, c))
                    .get(2, TimeUnit.MINUTES);
            assertFalse(s.isEmpty());
        }

        // A directory we fill ourselves (the model files only, none of the SDK's bookkeeping)
        // must be adopted as downloaded and run with no network.
        File prefilled = modelDir("prefilled-probe");
        deleteRecursively(prefilled);
        prefilled.mkdirs();
        for (String name : new String[] {"emo.tflite", "emo_meta.json", "emo_tokenizer.bin"}) {
            Files.copy(new File(installed, name).toPath(), new File(prefilled, name).toPath());
        }
        try (Emo adopted = new Emo(context, prefilled.getAbsolutePath())) {
            assertTrue("a self-filled directory should count as downloaded",
                    adopted.isDownloaded());
            List<EmoSuggestion> s = DesertAntClient.<List<EmoSuggestion>>run(
                    c -> adopted.suggestions("Let's get pizza", 3, EmojiSkinTone.DEFAULT, c))
                    .get(2, TimeUnit.MINUTES);
            assertFalse(s.isEmpty());
            Log.i(TAG, "prefilled adopted ok; files after use total=" + logFiles("prefilled", prefilled));
        } finally {
            deleteRecursively(prefilled);
        }

        // With no files and no network, inference must surface an error rather than hang or
        // pretend: this is the path the app has to guard so typing never starts a download.
        File empty = modelDir("offline-empty");
        deleteRecursively(empty);
        empty.mkdirs();
        try (Emo missing = new Emo(context, empty.getAbsolutePath())) {
            assertFalse(missing.isDownloaded());
            try {
                DesertAntClient.<List<EmoSuggestion>>run(
                        c -> missing.suggestions("hello", 3, EmojiSkinTone.DEFAULT, c))
                        .get(2, TimeUnit.MINUTES);
                fail("inference without assets or network should fail");
            } catch (ExecutionException e) {
                Log.i(TAG, "offline missing-model error=" + e.getCause().getClass().getName()
                        + ": " + e.getCause().getMessage());
            }
        } finally {
            deleteRecursively(empty);
        }
    }
}

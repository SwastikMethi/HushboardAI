// Reading audio the user imports, and handing audio back out: the decoder on real files
// (plain, compressed, too long, not audio at all) and the share intent with its read grant
// and its cleanup of older shared files.
package com.example.aikeyboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.aikeyboard.media.AudioDecoder;
import com.example.aikeyboard.media.AudioPipeline;
import com.example.aikeyboard.media.ShareResultController;
import com.example.aikeyboard.media.Voice.AudioClip;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class AudioImportTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private File file(String name) {
        return new File(context.getCacheDir(), name);
    }

    private static float peak(AudioClip clip) {
        float peak = 0;
        for (float sample : clip.samples) peak = Math.max(peak, Math.abs(sample));
        return peak;
    }

    @Test
    public void aWavFileIsReadAndBroughtToSpeechRate() throws Exception {
        File wav = file("tone.wav");
        Files.write(wav.toPath(),
                AudioPipeline.toWav(new AudioClip(TestAudio.tone(48_000, 1.0), 48_000)));

        AudioClip clip = AudioDecoder.decode(context, Uri.fromFile(wav), 60);

        assertEquals(AudioPipeline.SPEECH_RATE, clip.sampleRate);
        assertEquals(1.0, clip.seconds(), 0.05);
        assertEquals("the tone was written at half volume", 0.5, peak(clip), 0.05);
    }

    @Test
    public void aCompressedFileIsDecoded() throws Exception {
        File m4a = file("tone.m4a");
        TestAudio.writeAac(m4a, TestAudio.tone(44_100, 2.0), 44_100);

        AudioClip clip = AudioDecoder.decode(context, Uri.fromFile(m4a), 60);

        assertEquals(AudioPipeline.SPEECH_RATE, clip.sampleRate);
        // Compressed audio carries a little encoder padding at each end.
        assertEquals(2.0, clip.seconds(), 0.2);
        assertTrue("decoded audio should not be silence: " + peak(clip), peak(clip) > 0.2);
    }

    @Test
    public void aLongFileIsCutAtTheLimitInsteadOfFillingMemory() throws Exception {
        File wav = file("long.wav");
        Files.write(wav.toPath(),
                AudioPipeline.toWav(new AudioClip(TestAudio.tone(16_000, 5.0), 16_000)));

        AudioClip clip = AudioDecoder.decode(context, Uri.fromFile(wav), 2);

        assertTrue("seconds: " + clip.seconds(), clip.seconds() >= 2 && clip.seconds() < 3.5);
    }

    @Test
    public void aFileThatIsNotAudioIsRefusedWithAReason() throws Exception {
        File text = file("notes.txt");
        Files.write(text.toPath(), "this is not a recording".getBytes(StandardCharsets.UTF_8));
        try {
            AudioDecoder.decode(context, Uri.fromFile(text), 60);
            fail("a text file must not decode");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void sharedAudioIsAReadableWavBehindAOneOffReadGrant() throws Exception {
        AudioClip clip = new AudioClip(TestAudio.tone(16_000, 0.5), 16_000);

        Intent share = ShareResultController.audioShareIntent(context, clip);

        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("audio/wav", share.getType());
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Uri uri = share.getClipData().getItemAt(0).getUri();
        assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals("content", uri.getScheme());
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) read.write(buffer, 0, n);
        }
        assertArrayEquals(AudioPipeline.toWav(clip), read.toByteArray());
    }

    @Test
    public void olderSharedFilesAreRemovedTheNextTimeSomethingIsShared() throws Exception {
        File shared = new File(context.getCacheDir(), "shared");
        shared.mkdirs();
        File old = new File(shared, "voice-old.wav");
        Files.write(old.toPath(), new byte[] {1, 2, 3});
        assertTrue(old.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L));
        File recent = new File(shared, "voice-recent.wav");
        Files.write(recent.toPath(), new byte[] {1, 2, 3});

        ShareResultController.audioShareIntent(context,
                new AudioClip(TestAudio.tone(16_000, 0.1), 16_000));

        assertFalse("a file shared two hours ago should be gone", old.exists());
        // Something shared a moment ago may still be being read by the app it went to.
        assertTrue(recent.exists());
    }
}

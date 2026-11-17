// The app's own model downloader against a real local socket: complete transfers, resume,
// interruption, corruption, cancellation. The rule under test: only fully verified files ever
// appear under their final names.
package com.example.aikeyboard.models;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.aikeyboard.models.ModelDescriptor.ModelFile;
import com.example.aikeyboard.models.ModelDownloader.DownloadException;
import com.example.aikeyboard.models.ModelDownloader.Failure;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ModelDownloaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private RangeServer server;
    private File dir;
    private final ModelDownloader downloader = new ModelDownloader();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final byte[] big = randomBytes(300_000, 1);
    private final byte[] small = randomBytes(1_000, 2);

    @Before
    public void setUp() throws Exception {
        server = new RangeServer();
        dir = temp.newFolder("model");
        server.put("sub/big.bin", big);
        server.put("small.json", small);
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private List<ModelFile> files() throws Exception {
        return Arrays.asList(
                new ModelFile("sub/big.bin", big.length, sha256(big)),
                new ModelFile("small.json", small.length, sha256(small)));
    }

    private Failure failureOf(List<ModelFile> files) {
        try {
            downloader.fetch(server.baseUrl(), files, dir, cancelled, (done, total) -> { });
            fail("expected the download to fail");
            return null;
        } catch (DownloadException e) {
            return e.failure;
        }
    }

    @Test
    public void downloadsEveryFileAndReportsProgressUpToTheTotal() throws Exception {
        AtomicLong lastDone = new AtomicLong();
        AtomicLong lastTotal = new AtomicLong();
        downloader.fetch(server.baseUrl(), files(), dir, cancelled, (done, total) -> {
            lastDone.set(done);
            lastTotal.set(total);
        });

        assertArrayEquals(big, Files.readAllBytes(new File(dir, "sub/big.bin").toPath()));
        assertArrayEquals(small, Files.readAllBytes(new File(dir, "small.json").toPath()));
        assertEquals(big.length + small.length, lastTotal.get());
        assertEquals(lastTotal.get(), lastDone.get());
        assertTrue(ModelDownloader.isComplete(dir, files()));
    }

    @Test
    public void createsTheModelDirectoryOnAFirstDownload() throws Exception {
        // Found on a device: a directory that does not exist yet reports zero free space.
        File fresh = new File(temp.getRoot(), "models/emo/v1");
        downloader.fetch(server.baseUrl(), files(), fresh, cancelled, (done, total) -> { });
        assertTrue(ModelDownloader.isComplete(fresh, files()));
    }

    @Test
    public void resumesAPartialFileWithARangeRequest() throws Exception {
        File part = new File(dir, "sub/big.bin.part");
        part.getParentFile().mkdirs();
        Files.write(part.toPath(), Arrays.copyOf(big, 120_000));

        downloader.fetch(server.baseUrl(), files(), dir, cancelled, (done, total) -> { });

        assertTrue(server.requests.contains("sub/big.bin Range: bytes=120000-"));
        assertArrayEquals(big, Files.readAllBytes(new File(dir, "sub/big.bin").toPath()));
        assertFalse(part.exists());
    }

    @Test
    public void restartsFromZeroWhenTheServerIgnoresTheRange() throws Exception {
        File part = new File(dir, "sub/big.bin.part");
        part.getParentFile().mkdirs();
        Files.write(part.toPath(), Arrays.copyOf(big, 120_000));
        server.ignoreRange = true;

        downloader.fetch(server.baseUrl(), files(), dir, cancelled, (done, total) -> { });

        assertArrayEquals(big, Files.readAllBytes(new File(dir, "sub/big.bin").toPath()));
    }

    @Test
    public void anInterruptedTransferIsANetworkFailureAndNeverLooksComplete() throws Exception {
        server.dropAfterBytes = 50_000;

        assertEquals(Failure.NETWORK, failureOf(files()));

        assertFalse("no final file before verification", new File(dir, "sub/big.bin").exists());
        assertTrue("the partial bytes are kept for resuming",
                new File(dir, "sub/big.bin.part").length() > 0);
        assertFalse(ModelDownloader.isComplete(dir, files()));
    }

    @Test
    public void corruptContentIsDeletedAndNeverLooksComplete() throws Exception {
        byte[] tampered = big.clone();
        tampered[1234] ^= 0x55;
        server.put("sub/big.bin", tampered);

        assertEquals(Failure.CORRUPT, failureOf(files()));

        assertFalse(new File(dir, "sub/big.bin").exists());
        assertFalse("corrupt bytes must not be resumed from",
                new File(dir, "sub/big.bin.part").exists());
        assertFalse(ModelDownloader.isComplete(dir, files()));
    }

    @Test
    public void cancellingStopsTheTransferPartWay() throws Exception {
        try {
            downloader.fetch(server.baseUrl(), files(), dir, cancelled,
                    (done, total) -> cancelled.set(true));
            fail("expected cancellation");
        } catch (DownloadException e) {
            assertEquals(Failure.CANCELLED, e.failure);
        }
        assertFalse(new File(dir, "sub/big.bin").exists());
        assertFalse(ModelDownloader.isComplete(dir, files()));
    }

    @Test
    public void aFileMissingOnTheServerIsAServerFailure() throws Exception {
        List<ModelFile> withMissing = Arrays.asList(new ModelFile("nope.bin", 10, "00"));
        assertEquals(Failure.SERVER, failureOf(withMissing));
    }

    @Test
    public void filesAlreadyInPlaceAreNotFetchedAgain() throws Exception {
        Files.write(new File(dir, "small.json").toPath(), small);

        downloader.fetch(server.baseUrl(), files(), dir, cancelled, (done, total) -> { });

        assertFalse(server.requests.toString(), server.requests.contains("small.json"));
        assertTrue(ModelDownloader.isComplete(dir, files()));
    }

    @Test
    public void aDirectoryWithAWrongSizedFileIsNotComplete() throws Exception {
        new File(dir, "sub").mkdirs();
        Files.write(new File(dir, "sub/big.bin").toPath(), Arrays.copyOf(big, 100));
        Files.write(new File(dir, "small.json").toPath(), small);
        assertFalse(ModelDownloader.isComplete(dir, files()));
    }
}

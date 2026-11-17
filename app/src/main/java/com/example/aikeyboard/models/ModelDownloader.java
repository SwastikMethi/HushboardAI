// Fetches a model's pinned files into the app's own directory, where the vendor SDK adopts
// them. Written because the SDK's own download cannot be cancelled: this one stops when told,
// resumes with a Range request, and never leaves an unverified file under its final name.
package com.example.aikeyboard.models;

import com.example.aikeyboard.models.ModelDescriptor.ModelFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelDownloader {

    /** Why a download stopped. Each is shown differently and retried differently. */
    public enum Failure { NETWORK, STORAGE, CORRUPT, SERVER, CANCELLED }

    public static final class DownloadException extends Exception {
        public final Failure failure;

        DownloadException(Failure failure, Throwable cause) {
            super(failure.name(), cause);
            this.failure = failure;
        }
    }

    public interface Progress {
        void onBytes(long done, long total);
    }

    private static final String PART_SUFFIX = ".part";
    private static final int BUFFER_BYTES = 64 * 1024;

    /** True only when every file is present under its final name with its exact size. */
    public static boolean isComplete(File dir, List<ModelFile> files) {
        if (files.isEmpty()) return false;
        for (ModelFile file : files) {
            File target = new File(dir, file.path);
            if (!target.isFile() || target.length() != file.bytes) return false;
        }
        return true;
    }

    /**
     * Blocks until every file is downloaded and verified, or throws. Call off the main thread.
     * Setting {@code cancelled} stops the transfer within one buffer; partial bytes stay on
     * disk as "<name>.part" so the next call resumes instead of starting over.
     */
    public void fetch(String baseUrl, List<ModelFile> files, File dir, AtomicBoolean cancelled,
            Progress progress) throws DownloadException {
        long total = 0;
        long done = 0;
        for (ModelFile file : files) {
            total += file.bytes;
            done += bytesOnDisk(dir, file);
        }
        // Create the directory before asking about space: a path that does not exist yet
        // reports zero bytes free.
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new DownloadException(Failure.STORAGE, null);
        }
        if (dir.getUsableSpace() < total - done) {
            throw new DownloadException(Failure.STORAGE, null);
        }
        for (ModelFile file : files) {
            File target = new File(dir, file.path);
            if (target.isFile() && target.length() == file.bytes) continue;
            long before = done - bytesOnDisk(dir, file);
            fetchOne(baseUrl + file.path, file, target, cancelled, before, total, progress);
            done = before + file.bytes;
            progress.onBytes(done, total);
        }
    }

    private static long bytesOnDisk(File dir, ModelFile file) {
        File target = new File(dir, file.path);
        if (target.isFile() && target.length() == file.bytes) return file.bytes;
        File part = new File(target.getPath() + PART_SUFFIX);
        return part.isFile() ? Math.min(part.length(), file.bytes) : 0;
    }

    private void fetchOne(String url, ModelFile file, File target, AtomicBoolean cancelled,
            long doneBefore, long total, Progress progress) throws DownloadException {
        File part = new File(target.getPath() + PART_SUFFIX);
        File parent = part.getParentFile();
        if (parent != null) parent.mkdirs();
        long have = part.isFile() ? part.length() : 0;
        if (have > file.bytes) { // left over from a different revision
            part.delete();
            have = 0;
        }
        if (have < file.bytes) have = transfer(url, part, have, cancelled, doneBefore, total,
                progress);
        if (have != file.bytes) throw new DownloadException(Failure.NETWORK, null);

        // Only a file whose hash matches the pinned one may take the final name.
        if (!file.sha256.equals(sha256(part))) {
            part.delete();
            throw new DownloadException(Failure.CORRUPT, null);
        }
        if (!part.renameTo(target)) throw new DownloadException(Failure.STORAGE, null);
    }

    private long transfer(String url, File part, long have, AtomicBoolean cancelled,
            long doneBefore, long total, Progress progress) throws DownloadException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            // Not kept for reuse: an idle connection is closed by the server a minute or so
            // later, and that would show as network traffic from the app while typing.
            connection.setRequestProperty("Connection", "close");
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                have = 0; // the server sent the whole file; start the part file again
            } else if (code == 416) {
                part.delete(); // our offset no longer exists on the server; next try restarts
                throw new DownloadException(Failure.NETWORK, null);
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new DownloadException(Failure.SERVER, null);
            }
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, have > 0)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled.get()) throw new DownloadException(Failure.CANCELLED, null);
                    try {
                        out.write(buffer, 0, read);
                    } catch (IOException e) { // writing fails when the disk is full
                        throw new DownloadException(Failure.STORAGE, e);
                    }
                    have += read;
                    progress.onBytes(doneBefore + have, total);
                }
            }
            return have;
        } catch (IOException e) {
            throw new DownloadException(Failure.NETWORK, e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sha256(File file) throws DownloadException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new DownloadException(Failure.STORAGE, e);
        }
    }
}

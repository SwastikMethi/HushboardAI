// Everything the app knows about one model before it runs: where it belongs in the catalog,
// its vendor status, the exact files it needs, and the settings it offers.
package com.example.aikeyboard.models;

import java.util.Collections;
import java.util.List;

public final class ModelDescriptor {

    public enum Group { TEXT, VOICE, IMAGES, DRAWING }

    /** One weight file, pinned by size and SHA-256 exactly as the vendor SDK pins it. */
    public static final class ModelFile {
        public final String path;
        public final long bytes;
        public final String sha256;

        public ModelFile(String path, long bytes, String sha256) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    public final String id;
    public final String name;
    public final Group group;
    /** String resource: one line saying what the model does. */
    public final int summary;
    public final boolean beta;
    /** Weights ship inside the app (no download, and they cannot be deleted). */
    public final boolean bundled;
    /** Weight revision the SDK version is pinned to. */
    public final String revision;
    /** Empty until this build has captured and verified the model's file list. */
    public final List<ModelFile> files;
    public final List<ModelSettingDefinition> settings;

    ModelDescriptor(String id, String name, Group group, int summary, boolean beta,
            boolean bundled, String revision, List<ModelFile> files,
            List<ModelSettingDefinition> settings) {
        this.id = id;
        this.name = name;
        this.group = group;
        this.summary = summary;
        this.beta = beta;
        this.bundled = bundled;
        this.revision = revision;
        this.files = Collections.unmodifiableList(files);
        this.settings = Collections.unmodifiableList(settings);
    }

    public long totalBytes() {
        long total = 0;
        for (ModelFile file : files) total += file.bytes;
        return total;
    }

    /** Folder URL of the pinned revision; a file's URL is this plus its path. */
    public String baseUrl() {
        return "https://huggingface.co/desert-ant-labs/" + id + "/resolve/" + revision + "/";
    }

    /** False means the catalog shows "Coming later": there is nothing real to download. */
    public boolean available() {
        return bundled || !files.isEmpty();
    }
}

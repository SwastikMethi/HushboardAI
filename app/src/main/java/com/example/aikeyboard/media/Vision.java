// Value types for the image and drawing tools. A picture or a stroke lives in memory for the
// screen that asked for it; nothing here is written to disk unless the user shares it.
package com.example.aikeyboard.media;

import java.util.Collections;
import java.util.List;

public final class Vision {

    private Vision() {}

    /** A label the image model attached to a picture, with how sure it is (0 to 1). */
    public static final class Tag {
        public final String name;
        public final double confidence;

        public Tag(String name, double confidence) {
            this.name = name;
            this.confidence = confidence;
        }
    }

    /** What the image model made of one picture. */
    public static final class ImageFindings {
        /** The sort of picture it is, in the model's own word: photo, screenshot, document… */
        public final String kind;
        /** Most confident first. */
        public final List<Tag> tags;
        /** The model's own 0-to-1 score for how good the picture looks. */
        public final double aesthetic;
        public final int people;
        public final int pets;
        public final int faces;

        public ImageFindings(String kind, List<Tag> tags, double aesthetic, int people,
                int pets, int faces) {
            this.kind = kind;
            this.tags = Collections.unmodifiableList(tags);
            this.aesthetic = aesthetic;
            this.people = people;
            this.pets = pets;
            this.faces = faces;
        }
    }

    /**
     * The sensitive-image check for one picture. In the keyboard and the image tools a flag
     * is a warning, never a block; Kids Chat refuses to send a flagged picture.
     */
    public static final class Sensitivity {
        public final double score;
        public final boolean flagged;

        public Sensitivity(double score, boolean flagged) {
            this.score = score;
            this.flagged = flagged;
        }
    }

    /** The shape a stroke was recognised as, with a clean outline to draw in its place. */
    public static final class RecognizedShape {
        /** The model's own word for it: line, ellipse, rectangle, arrow, scribble… */
        public final String kind;
        /** Points as {x, y}, in the same coordinates the stroke was given in. */
        public final List<float[]> outline;
        /** Further strokes that belong to the shape: the heads of an arrow. */
        public final List<List<float[]>> extras;

        public RecognizedShape(String kind, List<float[]> outline, List<List<float[]>> extras) {
            this.kind = kind;
            this.outline = Collections.unmodifiableList(outline);
            this.extras = Collections.unmodifiableList(extras);
        }

        /** Whether the outline's last point joins back to its first. */
        public boolean closed() {
            return isClosed(kind);
        }
    }

    /** True for shapes that enclose an area (a rectangle), false for strokes (a line). */
    public static boolean isClosed(String kind) {
        return !("line".equals(kind) || "curve".equals(kind) || "arrow".equals(kind)
                || "scribble".equals(kind));
    }
}

// The arithmetic behind the image and drawing tools: how far a large picture is scaled down
// before it is held in memory, and which recognised shapes are drawn as closed outlines.
package com.example.aikeyboard.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VisionTest {

    @Test
    public void aPictureThatAlreadyFitsIsNotScaled() {
        assertEquals(1, ImageImport.sampleSize(800, 600, 1600));
        assertEquals(1, ImageImport.sampleSize(1600, 1600, 1600));
    }

    @Test
    public void aLargePictureIsHalvedUntilItsLongSideFits() {
        assertEquals(2, ImageImport.sampleSize(3000, 2000, 1600));
        assertEquals(4, ImageImport.sampleSize(4000, 3000, 1600));
        assertEquals(4, ImageImport.sampleSize(3000, 6000, 1600));
        assertEquals(8, ImageImport.sampleSize(12000, 9000, 1600));
    }

    @Test
    public void aPictureWithNoSizeIsLeftAlone() {
        // What the platform reports for a file it cannot read; the decode then fails cleanly.
        assertEquals(1, ImageImport.sampleSize(-1, -1, 1600));
    }

    @Test
    public void outlinesThatEncloseAnAreaAreClosedAndStrokesAreNot() {
        for (String kind : new String[] {"rectangle", "ellipse", "triangle", "diamond",
                "pentagon", "hexagon", "star", "heart", "cloud"}) {
            assertTrue(kind, Vision.isClosed(kind));
        }
        for (String kind : new String[] {"line", "curve", "arrow", "scribble"}) {
            assertFalse(kind, Vision.isClosed(kind));
        }
    }
}

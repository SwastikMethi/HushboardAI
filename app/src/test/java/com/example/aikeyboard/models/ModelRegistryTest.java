// The model catalog: all 13 Android models, grouped as the UI shows them, with the pinned
// file lists for the models this build can actually download.
package com.example.aikeyboard.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.models.ModelDescriptor.Group;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ModelRegistryTest {

    @Test
    public void listsAllThirteenModelsOnce() {
        Set<String> ids = new HashSet<>();
        for (ModelDescriptor model : ModelRegistry.all()) ids.add(model.id);
        assertEquals(13, ModelRegistry.all().size());
        assertEquals(13, ids.size());
    }

    @Test
    public void groupsMatchTheCatalogScreen() {
        assertEquals(5, ModelRegistry.inGroup(Group.TEXT).size());
        assertEquals(5, ModelRegistry.inGroup(Group.VOICE).size());
        assertEquals(2, ModelRegistry.inGroup(Group.IMAGES).size());
        assertEquals(1, ModelRegistry.inGroup(Group.DRAWING).size());
        assertEquals("emo", ModelRegistry.inGroup(Group.TEXT).get(0).id);
    }

    @Test
    public void findsAModelByIdAndReturnsNullForUnknownIds() {
        assertNotNull(ModelRegistry.find("redact"));
        assertNull(ModelRegistry.find("align"));
    }

    @Test
    public void tongueIsBundledAndNeedsNoDownload() {
        ModelDescriptor tongue = ModelRegistry.find("tongue");
        assertTrue(tongue.bundled);
        assertTrue(tongue.files.isEmpty());
    }

    @Test
    public void vendorBetaLabelsArePreserved() {
        assertTrue(ModelRegistry.find("toxic").beta);
        assertTrue(ModelRegistry.find("who").beta);
        assertTrue(ModelRegistry.find("eye").beta);
        assertFalse(ModelRegistry.find("emo").beta);
    }

    @Test
    public void pinnedFileListsAddUpToTheSizesTheSdkDownloaded() {
        assertEquals(10_962_213L, ModelRegistry.find("emo").totalBytes());
        assertEquals(24_324_954L, ModelRegistry.find("redact").totalBytes());
        assertEquals(103_272_098L, ModelRegistry.find("toxic").totalBytes());
        assertEquals(84_574_548L, ModelRegistry.find("gist").totalBytes());
        assertEquals(1_244_258_225L, ModelRegistry.find("voz").totalBytes());
        assertEquals(49_285_108L, ModelRegistry.find("clear").totalBytes());
        assertEquals(10_738_698L, ModelRegistry.find("ear").totalBytes());
        assertEquals(23_919_472L, ModelRegistry.find("uhm").totalBytes());
        assertEquals(33_306_046L, ModelRegistry.find("who").totalBytes());
        assertEquals(66_319_130L, ModelRegistry.find("eye").totalBytes());
        assertEquals(9_618_016L, ModelRegistry.find("moderator").totalBytes());
        assertEquals(976_447L, ModelRegistry.find("shapes").totalBytes());
    }

    @Test
    public void theSpeechModelDeclaresTheMemoryItNeedsAndSmallModelsDeclareNone() {
        long gib = 1L << 30;
        // Measured on a phone: the app holds about 5 GB with Voz loaded. A phone sold as
        // 6 GB reports about 5.6 GiB and cannot hold that; one sold as 8 GB reports about 7.
        assertFalse(ModelRegistry.find("voz").fitsRam(4 * gib));
        assertFalse(ModelRegistry.find("voz").fitsRam((long) (5.6 * gib)));
        assertTrue(ModelRegistry.find("voz").fitsRam((long) (6.9 * gib)));
        // Measured: about 1.5 GB with Eye loaded.
        assertFalse(ModelRegistry.find("eye").fitsRam(gib));
        assertTrue(ModelRegistry.find("eye").fitsRam(3 * gib));
        assertTrue(ModelRegistry.find("emo").fitsRam(gib));
        assertTrue(ModelRegistry.find("who").fitsRam(gib));
    }

    @Test
    public void fileUrlsPointAtThePinnedRevision() {
        ModelDescriptor emo = ModelRegistry.find("emo");
        assertEquals("https://huggingface.co/desert-ant-labs/emo/resolve/v0.7.0/",
                emo.baseUrl());
    }

    @Test
    public void everyModelInTheCatalogCanReallyBeInstalled() {
        for (ModelDescriptor model : ModelRegistry.all()) {
            assertTrue(model.id, model.available());
        }
    }

    @Test
    public void aModelWithNoPinnedFilesWouldBeListedAsComingLaterNotAsADownload() {
        ModelDescriptor notCaptured = new ModelDescriptor("x", "X", Group.TEXT, 0, false,
                false, "v1", java.util.List.of(), java.util.List.of());
        assertFalse(notCaptured.available());
    }
}

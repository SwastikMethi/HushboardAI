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
    }

    @Test
    public void fileUrlsPointAtThePinnedRevision() {
        ModelDescriptor emo = ModelRegistry.find("emo");
        assertEquals("https://huggingface.co/desert-ant-labs/emo/resolve/v0.7.0/",
                emo.baseUrl());
    }

    @Test
    public void aModelIsOnlyAvailableWhenItIsBundledOrHasPinnedFiles() {
        assertTrue(ModelRegistry.find("emo").available());
        assertTrue(ModelRegistry.find("tongue").available());
        // Not yet captured: these must show as coming later, never as a working download.
        assertFalse(ModelRegistry.find("voz").available());
    }
}

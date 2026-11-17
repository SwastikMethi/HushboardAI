// Checks the shape of each keyboard layer: the right keys, once each, in rows that fit.
package com.example.aikeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.aikeyboard.ime.KeyboardLayout.Key;
import com.example.aikeyboard.ime.KeyboardLayout.Layer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class KeyboardLayoutTest {

    private static List<Key> flat(Layer layer) {
        List<Key> all = new ArrayList<>();
        for (List<Key> row : KeyboardLayout.rows(layer)) all.addAll(row);
        return all;
    }

    private static boolean hasCode(Layer layer, int code) {
        for (Key key : flat(layer)) if (key.code == code) return true;
        return false;
    }

    private static Set<String> textLabels(Layer layer) {
        Set<String> labels = new HashSet<>();
        for (Key key : flat(layer)) if (key.isText()) labels.add(key.label);
        return labels;
    }

    @Test
    public void lettersLayerIsQwertyInFourRows() {
        List<List<Key>> rows = KeyboardLayout.rows(Layer.LETTERS);
        assertEquals(4, rows.size());
        assertEquals(10, rows.get(0).size());
        assertEquals(9, rows.get(1).size());
        assertEquals(9, rows.get(2).size());
        assertEquals("q", rows.get(0).get(0).label);
        assertEquals("p", rows.get(0).get(9).label);
        assertEquals("a", rows.get(1).get(0).label);
        assertEquals(KeyboardLayout.CODE_SHIFT, rows.get(2).get(0).code);
        assertEquals(KeyboardLayout.CODE_BACKSPACE, rows.get(2).get(8).code);
    }

    @Test
    public void lettersLayerHasEveryLetterExactlyOnce() {
        Set<String> labels = textLabels(Layer.LETTERS);
        for (char c = 'a'; c <= 'z'; c++) {
            assertTrue("missing " + c, labels.contains(String.valueOf(c)));
        }
    }

    @Test
    public void symbolsLayerHasEveryDigit() {
        Set<String> labels = textLabels(Layer.SYMBOLS);
        for (char c = '0'; c <= '9'; c++) {
            assertTrue("missing " + c, labels.contains(String.valueOf(c)));
        }
    }

    @Test
    public void everyLayerCanDeleteSpaceAndEnter() {
        for (Layer layer : Layer.values()) {
            assertTrue(layer + " backspace", hasCode(layer, KeyboardLayout.CODE_BACKSPACE));
            assertTrue(layer + " space", hasCode(layer, KeyboardLayout.CODE_SPACE));
            assertTrue(layer + " enter", hasCode(layer, KeyboardLayout.CODE_ENTER));
        }
    }

    @Test
    public void everyLayerCanReachTheOthers() {
        assertTrue(hasCode(Layer.LETTERS, KeyboardLayout.CODE_SYMBOLS));
        assertTrue(hasCode(Layer.SYMBOLS, KeyboardLayout.CODE_LETTERS));
        assertTrue(hasCode(Layer.SYMBOLS, KeyboardLayout.CODE_MORE_SYMBOLS));
        assertTrue(hasCode(Layer.MORE_SYMBOLS, KeyboardLayout.CODE_LETTERS));
        assertTrue(hasCode(Layer.MORE_SYMBOLS, KeyboardLayout.CODE_SYMBOLS));
    }

    @Test
    public void noLayerRepeatsACharacterKey() {
        for (Layer layer : Layer.values()) {
            int textKeys = 0;
            for (Key key : flat(layer)) if (key.isText()) textKeys++;
            assertEquals(layer + " has a duplicate character", textKeys, textLabels(layer).size());
        }
    }

    @Test
    public void noRowIsWiderThanTenKeyUnits() {
        for (Layer layer : Layer.values()) {
            for (List<Key> row : KeyboardLayout.rows(layer)) {
                float width = 0;
                for (Key key : row) width += key.weight;
                assertTrue(layer + " row too wide: " + width, width <= 10.001f);
            }
        }
    }
}

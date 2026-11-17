// Word completions and one-edit corrections from a frequency-ordered word list.
package com.example.aikeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class LocalWordSuggesterTest {

    // Most frequent first, as in the bundled list.
    private final LocalWordSuggester suggester = new LocalWordSuggester(Arrays.asList(
            "the", "that", "this", "they", "there", "then", "them", "than", "think", "thing",
            "good", "great", "going", "perfect", "tomorrow", "hello", "help"));

    @Test
    public void completesAPrefixMostFrequentFirst() {
        assertEquals(Arrays.asList("the", "that", "this"), suggester.suggest("th", 3));
    }

    @Test
    public void doesNotSuggestTheWordAlreadyTyped() {
        List<String> suggestions = suggester.suggest("the", 3);
        assertFalse(suggestions.contains("the"));
        assertEquals(Arrays.asList("they", "there", "then"), suggestions);
    }

    @Test
    public void repairsATranspositionWhenNothingCompletes() {
        assertEquals("the", suggester.suggest("teh", 3).get(0));
    }

    @Test
    public void repairsAMissingLetter() {
        assertTrue(suggester.suggest("tomorow", 3).contains("tomorrow"));
    }

    @Test
    public void doesNotRepairARealWord() {
        // "then" is a word; "than"/"them" are one edit away but must not be offered as fixes.
        assertEquals(List.of(), suggester.suggest("then", 3));
    }

    @Test
    public void keepsTheCapitalisationTheUserTyped() {
        assertEquals(Arrays.asList("The", "That", "This"), suggester.suggest("Th", 3));
        assertEquals(Arrays.asList("HELLO", "HELP"), suggester.suggest("HEL", 3));
    }

    @Test
    public void offersNothingForEmptyOrNonLetterInput() {
        assertEquals(List.of(), suggester.suggest("", 3));
        assertEquals(List.of(), suggester.suggest("123", 3));
    }

    @Test
    public void respectsTheLimit() {
        assertEquals(2, suggester.suggest("th", 2).size());
    }

    @Test
    public void loadsAWhitespaceSeparatedListInFrequencyOrder() throws Exception {
        LocalWordSuggester loaded = LocalWordSuggester.load(new ByteArrayInputStream(
                "great good\ngoing\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Arrays.asList("great", "good", "going"), loaded.suggest("g", 3));
    }
}

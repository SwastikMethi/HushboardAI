// Local word completions and simple corrections from a bundled frequency-ordered word list.
// This is not a language model and is unrelated to the Desert Ant models.
package com.example.aikeyboard.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LocalWordSuggester {

    // ponytail: single-word frequencies only, so no next-word prediction and no context.
    // Upgrade path: a larger licensed list (e.g. AOSP LatinIME's) plus bigram counts.
    private final String[] sorted;
    private final Map<String, Integer> rank = new HashMap<>();

    /** @param wordsByFrequency lower-case words, most frequent first */
    public LocalWordSuggester(List<String> wordsByFrequency) {
        for (String word : wordsByFrequency) rank.putIfAbsent(word, rank.size());
        sorted = rank.keySet().toArray(new String[0]);
        Arrays.sort(sorted);
    }

    /** Reads a whitespace-separated word list, most frequent first. */
    public static LocalWordSuggester load(InputStream in) throws IOException {
        List<String> words = new ArrayList<>();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String word : line.trim().split("\\s+")) {
                    if (!word.isEmpty()) words.add(word);
                }
            }
        }
        return new LocalWordSuggester(words);
    }

    /**
     * Suggestions for the word being typed: completions first, then one-edit repairs, but
     * repairs only when the typed text is not itself a word. Capitalisation follows the input.
     */
    public List<String> suggest(String typed, int max) {
        String lower = typed.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || !lower.chars().allMatch(Character::isLetter)) return List.of();

        Set<String> found = new LinkedHashSet<>(completions(lower));
        if (!rank.containsKey(lower)) found.addAll(repairs(lower));

        List<String> result = new ArrayList<>();
        for (String word : found) {
            if (result.size() == max) break;
            result.add(matchCase(word, typed));
        }
        return result;
    }

    private List<String> completions(String prefix) {
        int from = Arrays.binarySearch(sorted, prefix);
        if (from < 0) from = -from - 1;
        List<String> matches = new ArrayList<>();
        for (int i = from; i < sorted.length && sorted[i].startsWith(prefix); i++) {
            if (!sorted[i].equals(prefix)) matches.add(sorted[i]);
        }
        matches.sort(Comparator.comparing(rank::get));
        return matches;
    }

    // Known words one edit away: a dropped, doubled, swapped or wrong letter.
    private List<String> repairs(String word) {
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i <= word.length(); i++) {
            String head = word.substring(0, i);
            String tail = word.substring(i);
            if (!tail.isEmpty()) {
                candidates.add(head + tail.substring(1));
                if (tail.length() > 1) {
                    candidates.add(head + tail.charAt(1) + tail.charAt(0) + tail.substring(2));
                }
            }
            for (char c = 'a'; c <= 'z'; c++) {
                candidates.add(head + c + tail);
                if (!tail.isEmpty()) candidates.add(head + c + tail.substring(1));
            }
        }
        List<String> known = new ArrayList<>();
        for (String candidate : candidates) {
            if (rank.containsKey(candidate) && !candidate.equals(word)) known.add(candidate);
        }
        known.sort(Comparator.comparing(rank::get));
        return known;
    }

    private static String matchCase(String word, String typed) {
        if (typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT))) {
            return word.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }
}

package org.instruct.jobenginespring.application.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchTextNormalizerTests {

    @Test
    void prefixesRetainTheOwningQueryToken() {
        var prefixes = SearchTextNormalizer.prefixes(List.of("java", "c#"));

        assertEquals(List.of("j", "ja", "jav", "java", "c", "c#"), prefixes.values());
        assertEquals(List.of("java", "java", "java", "java", "c#", "c#"), prefixes.owners());
        assertEquals(new SearchTextNormalizer.QueryPrefixes(List.of(), List.of()),
                SearchTextNormalizer.prefixes(List.of()));
        assertEquals(List.of("𐐀", "𐐀x"), SearchTextNormalizer.prefixes(List.of("𐐀x")).values());
    }

    @Test
    void queryPrefixesRejectMismatchedParallelLists() {
        assertThrows(IllegalArgumentException.class,
                () -> new SearchTextNormalizer.QueryPrefixes(List.of("j"), List.of()));
    }
}

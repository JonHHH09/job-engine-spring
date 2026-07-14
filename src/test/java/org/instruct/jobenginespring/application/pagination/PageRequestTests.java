package org.instruct.jobenginespring.application.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageRequestTests {

    @Test
    void defaultsAndBoundsPageSize() {
        assertEquals(20, PageRequest.of(null, null).limit());
        assertEquals(100, PageRequest.of(100, null).limit());
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, null));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(101, null));
    }

    @Test
    void pageCopiesItemsAndCarriesOpaqueAnchor() {
        var cursor = UUID.randomUUID();
        var page = new Page<>(List.of("one"), cursor);

        assertEquals(List.of("one"), page.items());
        assertEquals(cursor, page.nextCursor());
        assertNull(new Page<>(List.of(), null).nextCursor());
        assertThrows(NullPointerException.class, () -> new Page<>(null, null));
    }

    @Test
    void searchCandidatesValidateCountMetadata() {
        assertEquals(-1, new SearchCandidates<>(-1, List.of("one")).totalMatches());
        assertThrows(IllegalArgumentException.class, () -> new SearchCandidates<>(-2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SearchCandidates<>(0, List.of("one")));
        assertThrows(NullPointerException.class, () -> new SearchCandidates<>(0, null));
    }
}

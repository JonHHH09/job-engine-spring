package org.instruct.jobenginespring.application.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageRequestTests {

    @Test
    void defaultsAndBoundsPageSize() {
        assertEquals(20, PageRequest.of(null, null, "jobs", "all").limit());
        assertEquals(100, PageRequest.of(100, null, "jobs", "all").limit());
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, null, "jobs", "all"));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(101, null, "jobs", "all"));
    }

    @Test
    void pageCopiesItemsAndCarriesOpaqueAnchor() {
        var cursor = "opaque-cursor";
        var page = new Page<>(List.of("one"), cursor);

        assertEquals(List.of("one"), page.items());
        assertEquals(cursor, page.nextCursor());
        assertNull(new Page<>(List.of(), null).nextCursor());
        assertThrows(NullPointerException.class, () -> new Page<>(null, null));
    }

    @Test
    void searchCandidatesValidateCountMetadata() {
        assertEquals(1, new SearchCandidates<>(1, List.of("one")).matchedCount());
        assertEquals(-1, new SearchCandidates<>(-1, List.of()).matchedCount());
        assertThrows(IllegalArgumentException.class, () -> new SearchCandidates<>(-2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SearchCandidates<>(0, List.of("one")));
        assertThrows(NullPointerException.class, () -> new SearchCandidates<>(0, null));
    }

    @Test
    void cursorRoundTripsAndRejectsMalformedOrMismatchedValues() {
        var request = PageRequest.of(10, null, "jobs", "all");
        var cursor = request.nextCursor(Instant.parse("2026-07-14T12:00:00Z"),
                Instant.parse("2026-07-13T12:00:00Z"), UUID.randomUUID());
        var resumed = PageRequest.of(10, cursor, "jobs", "all");

        assertEquals(Instant.parse("2026-07-14T12:00:00Z"), resumed.cursor().snapshotAt());
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, "not-base64", "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, encoded("v1|jobs"), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                encoded("v2|jobs|fingerprint|2026-07-14T12:00:00Z|2026-07-13T12:00:00Z|"
                        + UUID.randomUUID()), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                encoded("v1|jobs|fingerprint|2026-07-14T12:00:00Z|2026-07-13T12:00:00Z|not-a-uuid"),
                "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, cursor, "profiles", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, cursor, "jobs", "filtered"));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(10, null, null, "all"));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(10, null, " ", "all"));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(10, null, "bad|scope", "all"));
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

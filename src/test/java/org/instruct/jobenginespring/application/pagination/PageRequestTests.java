package org.instruct.jobenginespring.application.pagination;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                tamper(cursor, 3, "2026-07-15T12:00:00Z"), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                tamper(cursor, 5, UUID.randomUUID().toString()), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                tamper(cursor, 1, "profiles"), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                tamper(cursor, 2, "0".repeat(64)), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, "not-base64", "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10, "x".repeat(4_097), "jobs", "all"));
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

    @Test
    void authenticatedMalformedPayloadsAreStillRejected() throws Exception {
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                authenticated("v1|jobs"), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                authenticated("v2|jobs|fingerprint|2026-07-14T12:00:00Z|2026-07-13T12:00:00Z|"
                        + UUID.randomUUID()), "jobs", "all"));
        assertThrows(RuntimeException.class, () -> PageRequest.of(10,
                authenticated("v1|jobs|fingerprint|2026-07-14T12:00:00Z|2026-07-13T12:00:00Z|not-a-uuid"),
                "jobs", "all"));
    }

    @Test
    void cursorKeySupportsStableConfiguredSecretsAndSecureEphemeralFallbacks() {
        var configured = "0123456789abcdef0123456789abcdef";

        assertArrayEquals(configured.getBytes(StandardCharsets.UTF_8), PageRequest.cursorKey(configured));
        assertEquals(32, PageRequest.cursorKey(null).length);
        assertEquals(32, PageRequest.cursorKey(" ").length);
        assertThrows(IllegalStateException.class, () -> PageRequest.cursorKey("too-short"));
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String tamper(String cursor, int field, String replacement) {
        var envelope = cursor.split("\\.", -1);
        var parts = new String(Base64.getUrlDecoder().decode(envelope[0]), StandardCharsets.UTF_8)
                .split("\\|", -1);
        parts[field] = replacement;
        return encoded(String.join("|", parts)) + "." + envelope[1];
    }

    private static String authenticated(String value) throws Exception {
        var payload = value.getBytes(StandardCharsets.UTF_8);
        var signer = PageRequest.class.getDeclaredMethod("sign", byte[].class);
        signer.setAccessible(true);
        var signature = (byte[]) signer.invoke(null, (Object) payload);
        return encoded(value) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
}

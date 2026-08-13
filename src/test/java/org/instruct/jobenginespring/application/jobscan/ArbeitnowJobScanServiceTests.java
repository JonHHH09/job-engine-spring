package org.instruct.jobenginespring.application.jobscan;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArbeitnowJobScanServiceTests {

    @Test
    void defaultsAndNullRequestAreSafeAndExposeSourceMetadata() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> {
            calls.incrementAndGet();
            return page(List.of(job("one", "Acme", "Java Engineer", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1_700_000_000L)), false);
        });

        ArbeitnowJobScanService.ScanResult result = service.scan(null);

        assertEquals(1, calls.get());
        assertEquals("arbeitnow", result.source());
        assertEquals("https://www.arbeitnow.com/api/job-board-api", result.sourceDocsUrl());
        assertEquals(1, result.pagesScanned());
        assertEquals(1, result.recordsInspected());
        assertNull(result.nextCursor());
        assertTrue(result.consistencyNotice().contains("not a snapshot"));
        assertEquals("https://arbeitnow.com/view/one", result.jobs().getFirst().canonicalUrl());
        assertNotNull(result.jobs().getFirst().candidateToken());
    }

    @Test
    void appliesAllFilterGroupsInSourceOrderAndForwardsVisaFilter() {
        AtomicInteger visaCalls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, visa) -> {
            if (Boolean.TRUE.equals(visa)) {
                visaCalls.incrementAndGet();
            }
            return page(List.of(
                    job("first", "Acme", "Senior Java", "<p>Platform &amp; APIs</p>", List.of("Java", "Cloud"), List.of("full-time"), "Berlin", 1L),
                    job("wrong-tag", "Acme", "Senior Java", "Description", List.of("Kotlin"), List.of("full-time"), "Berlin", 2L),
                    job("wrong-type", "Acme", "Senior Java", "Description", List.of("Java"), List.of("contract"), "Berlin", 3L),
                    job("second", "Acme", "Senior Java", "Description", List.of("Cloud"), List.of("full-time"), "Berlin", 4L)
            ), false);
        });

        ArbeitnowJobScanService.ScanResult result = service.scan(new ArbeitnowJobScanService.ScanRequest(
                "JAVA acme", "ERLIN", true, true, List.of("java", "cloud"), List.of("FULL-TIME"), 100, 1, null
        ));

        assertEquals(1, visaCalls.get());
        assertEquals(List.of("first", "second"), result.jobs().stream().map(ArbeitnowJobScanService.ScannedJob::slug).toList());
        assertEquals("Platform & APIs", result.jobs().getFirst().descriptionExcerpt());
    }

    @Test
    void cursorContinuesWithinPageWithoutSkippingAndRejectsTamperingOrFilterMismatch() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("one", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L),
                job("two", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 2L),
                job("three", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 3L)
        ), false));
        ArbeitnowJobScanService.ScanRequest request = new ArbeitnowJobScanService.ScanRequest("java", null, null, null, null, null, 1, 1, null);

        ArbeitnowJobScanService.ScanResult first = service.scan(request);
        ArbeitnowJobScanService.ScanResult second = service.scan(withCursor(request, first.nextCursor()));
        ArbeitnowJobScanService.ScanResult third = service.scan(withCursor(request, second.nextCursor()));

        assertEquals("one", first.jobs().getFirst().slug());
        assertEquals("two", second.jobs().getFirst().slug());
        assertEquals("three", third.jobs().getFirst().slug());
        assertNull(third.nextCursor());
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, mutate(first.nextCursor()))));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest("different", null, null, null, null, null, 1, 1, first.nextCursor())));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, "not-base64")));
    }

    @Test
    void rejectsCursorsAcrossCommaAmbiguousTagFilterListsWhileRetainingCanonicalOrderAndCaseSemantics() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("comma-one", "Acme", "Java", "Description", List.of("java,spring"), List.of("full-time"), "Berlin", 1L),
                job("java", "Acme", "Java", "Description", List.of("java"), List.of("full-time"), "Berlin", 2L),
                job("comma-two", "Acme", "Java", "Description", List.of("java,spring"), List.of("full-time"), "Berlin", 3L),
                job("spring", "Acme", "Java", "Description", List.of("spring"), List.of("full-time"), "Berlin", 4L)
        ), false));
        ArbeitnowJobScanService.ScanRequest oneCommaDelimitedValue = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("java,spring"), null, 1, 1, null);
        ArbeitnowJobScanService.ScanRequest twoValues = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("java", "spring"), null, 1, 1, null);

        String oneValueCursor = service.scan(oneCommaDelimitedValue).nextCursor();
        String twoValuesCursor = service.scan(twoValues).nextCursor();

        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(twoValues, oneValueCursor)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(oneCommaDelimitedValue, twoValuesCursor)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(twoValues, "not-base64")));

        ArbeitnowJobScanService.ScanRequest canonicalOrderAndCase = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("Java", "Spring"), null, 1, 1, null);
        ArbeitnowJobScanService.ScanRequest equivalentOrderAndCase = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("spring", "java"), null, 1, 1, null);
        String canonicalCursor = service.scan(canonicalOrderAndCase).nextCursor();

        assertEquals("spring", service.scan(withCursor(equivalentOrderAndCase, canonicalCursor)).jobs().getFirst().slug());
    }

    @Test
    void rejectsCursorsAcrossDelimiterBearingQueryAndLocationFilters() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> calls.getAndIncrement() == 0
                ? page(List.of(
                job("first", "Acme", "java|berlin", "Description", List.of("Java"), List.of("full-time"), "x", 1L),
                job("second", "Acme", "java|berlin", "Description", List.of("Java"), List.of("full-time"), "x", 2L)
        ), false)
                : page(List.of(
                job("other-first", "Acme", "java", "Description", List.of("Java"), List.of("full-time"), "berlin|x", 3L),
                job("other-second", "Acme", "java", "Description", List.of("Java"), List.of("full-time"), "berlin|x", 4L)
        ), false));
        ArbeitnowJobScanService.ScanRequest firstFilter = new ArbeitnowJobScanService.ScanRequest(
                "java|berlin", "x", null, null, null, null, 1, 1, null);
        ArbeitnowJobScanService.ScanRequest secondFilter = new ArbeitnowJobScanService.ScanRequest(
                "java", "berlin|x", null, null, null, null, 1, 1, null);

        String cursor = service.scan(firstFilter).nextCursor();

        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(secondFilter, cursor)));
    }

    @Test
    void rejectsMalformedSurrogateFilterValuesBeforeTheyCanShareACursorFingerprintWithQuestionMarks() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("surrogate-one", "Acme", "Java", "Description", List.of("\uD800"), List.of("full-time"), "Berlin", 1L),
                job("surrogate-two", "Acme", "Java", "Description", List.of("\uD800"), List.of("full-time"), "Berlin", 2L),
                job("question-one", "Acme", "Java", "Description", List.of("?"), List.of("full-time"), "Berlin", 3L),
                job("question-two", "Acme", "Java", "Description", List.of("?"), List.of("full-time"), "Berlin", 4L)
        ), false));
        ArbeitnowJobScanService.ScanRequest malformedSurrogate = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("\uD800"), null, 1, 1, null);
        ArbeitnowJobScanService.ScanRequest questionMark = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("?"), null, 1, 1, null);

        assertThrows(IllegalArgumentException.class, () -> service.scan(malformedSurrogate));
        assertNotNull(service.scan(questionMark).nextCursor());
    }

    @Test
    void rejectsUnpairedSurrogatesAtEveryFingerprintFilterBoundary() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(), false));

        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(
                "\uD800", null, null, null, null, null, 1, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(
                null, "\uDC00", null, null, null, null, 1, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("\uD800"), null, 1, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, null, List.of("\uDC00"), 1, 1, null)));
    }

    @Test
    void rejectsHighSurrogateFollowedByNonLowSurrogateDuringRequestValidation() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> {
            calls.incrementAndGet();
            return page(List.of(), false);
        });

        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(
                "\uD800x", null, null, null, null, null, 1, 1, null)));
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsValidUnicodeAndRetainsCursorCompatibilityForDelimiterBearingFilterValues() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("unicode-one", "Acme", "Java", "Description", List.of("développeur, cloud 🚀"), List.of("full-time"), "Berlin", 1L),
                job("unicode-two", "Acme", "Java", "Description", List.of("développeur, cloud 🚀"), List.of("full-time"), "Berlin", 2L)
        ), false));
        ArbeitnowJobScanService.ScanRequest request = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of("Développeur, Cloud 🚀"), null, 1, 1, null);

        String cursor = service.scan(request).nextCursor();

        assertEquals("unicode-two", service.scan(withCursor(request, cursor)).jobs().getFirst().slug());
    }

    @Test
    void treatsAbsentAndEmptyTagFiltersAsCursorEquivalent() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("one", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L),
                job("two", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 2L)
        ), false));
        ArbeitnowJobScanService.ScanRequest absentTags = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, null, null, 1, 1, null);
        ArbeitnowJobScanService.ScanRequest emptyTags = new ArbeitnowJobScanService.ScanRequest(
                null, null, null, null, List.of(), null, 1, 1, null);

        String cursor = service.scan(absentTags).nextCursor();

        assertEquals("two", service.scan(withCursor(emptyTags, cursor)).jobs().getFirst().slug());
    }

    @Test
    void scansAcrossPagesAndCapsRequestsAtMaxPages() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((page, _) -> {
            calls.incrementAndGet();
            return switch (page) {
                case 1 -> page(List.of(job("first", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L)), true);
                case 2 -> page(List.of(job("second", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 2L)), true);
                default -> page(List.of(job("third", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 3L)), false);
            };
        });

        ArbeitnowJobScanService.ScanResult first = service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 100, 2, null));

        assertEquals(List.of("first", "second"), first.jobs().stream().map(ArbeitnowJobScanService.ScannedJob::slug).toList());
        assertEquals(2, calls.get());
        assertNotNull(first.nextCursor());
        ArbeitnowJobScanService.ScanResult continued = service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 100, 1, first.nextCursor()));
        assertEquals(List.of("third"), continued.jobs().stream().map(ArbeitnowJobScanService.ScannedJob::slug).toList());
    }

    @Test
    void enforcesBoundsAndNormalizesOutputSafely() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("slug", "A\u0000cme", "Title", "<script>private()</script><style>.x{}</style><p>Hello&nbsp; &lt;world&gt;\u0001</p>", List.of("Java"), List.of("full-time"), "Paris", Long.MAX_VALUE)
        ), false));

        ArbeitnowJobScanService.ScanResult result = service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 100, 1, null));

        assertEquals("Hello", result.jobs().getFirst().descriptionExcerpt());
        assertEquals("A cme", result.jobs().getFirst().company());
        assertNull(result.jobs().getFirst().postedAt());
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 0, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 101, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 6, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest("x".repeat(257), null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest("x ".repeat(17), null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, "x".repeat(129), null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, List.of(" "), null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"), null, null, null, null)));
    }

    @Test
    void rejectsCursorForgedWithTheFormerPublicSha256SchemeAndOutOfRangeValues() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> {
            calls.incrementAndGet();
            return page(List.of(), false);
        });
        ArbeitnowJobScanService.ScanRequest request = new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 1, null);

        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, formerPublicCursor("1:0:" + formerFingerprint()))));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, formerPublicCursor("-1:0:" + formerFingerprint()))));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, formerPublicCursor("1:-1:" + formerFingerprint()))));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, formerPublicCursor(Integer.MAX_VALUE + ":0:" + formerFingerprint()))));
        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, formerPublicCursor("1:" + Integer.MAX_VALUE + ":" + formerFingerprint()))));
        assertEquals(0, calls.get());
    }

    @Test
    void invalidatesCursorsAcrossServiceInstancesAndDefensivelyCopiesTestKeys() {
        byte[] firstKey = new byte[32];
        ArbeitnowJobScanService first = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("one", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L),
                job("two", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 2L)
        ), false), firstKey);
        ArbeitnowJobScanService.ScanRequest request = new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 1, null);
        String cursor = first.scan(request).nextCursor();
        firstKey[0] = 1;

        assertEquals("two", first.scan(withCursor(request, cursor)).jobs().getFirst().slug());
        byte[] restartedKey = new byte[32];
        restartedKey[0] = 2;
        ArbeitnowJobScanService restarted = new ArbeitnowJobScanService((_, _) -> page(List.of(), false), restartedKey);
        assertThrows(IllegalArgumentException.class, () -> restarted.scan(withCursor(request, cursor)));
    }

    @Test
    void rejectsHostileSlugsAndOnlyExposesValidatedCanonicalUrls() {
        List<String> hostileSlugs = List.of("", "two/segments", "back\\slash", "query?x", "fragment#x", "percent%2f", "white space", "line\nbreak", "café");
        for (String slug : hostileSlugs) {
            ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                    job(slug, "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L)
            ), false));
            assertThrows(IllegalArgumentException.class, () -> service.scan(null), slug);
        }

        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("valid-Slug-42", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false));
        assertEquals("https://arbeitnow.com/view/valid-Slug-42", service.scan(null).jobs().getFirst().canonicalUrl());
    }

    @Test
    void rejectsOverlongStructuralSlugRatherThanTruncatingItIntoAnotherCanonicalUrl() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("a".repeat(257), "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false));

        assertThrows(IllegalArgumentException.class, () -> service.scan(null));
    }

    @Test
    void rejectsMarkupOnlyDescriptionBeforeCandidateTokenIssuance() {
        AtomicInteger clockCalls = new AtomicInteger();
        Clock tokenClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { clockCalls.incrementAndGet(); return Instant.parse("2026-07-29T00:00:00Z"); }
        };
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(tokenClock, new byte[32]);
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("markup-only", "Acme", "Java", "<script>private()</script><style>.hidden {}</style>",
                        List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false), new byte[32], codec);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.scan(null));

        assertEquals("Invalid Arbeitnow upstream data", exception.getMessage());
        assertEquals(0, clockCalls.get());
    }

    @Test
    void rejectsMarkupOnlyTitleAndDescriptionBeforeCandidateTokenIssuance() {
        AtomicInteger clockCalls = new AtomicInteger();
        Clock tokenClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { clockCalls.incrementAndGet(); return Instant.parse("2026-07-29T00:00:00Z"); }
        };
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(tokenClock, new byte[32]);
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("markup-only-title", "Acme", "<script>private()</script><style>.hidden {}</style>", "Description",
                        List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false), new byte[32], codec);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.scan(null));

        assertEquals("Invalid Arbeitnow upstream data", exception.getMessage());
        assertEquals(0, clockCalls.get());
    }

    @Test
    void normalizesEveryExposedTextFieldAndIssuesTokensForMaximalBoundedScanOutput() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(
                java.time.Clock.fixed(java.time.Instant.parse("2026-07-27T10:00:00Z"), java.time.ZoneOffset.UTC), new byte[32]);
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("valid-slug", "<b>Acme</b>\t<script>private()</script>", "<i>Platform</i>\n<style>x</style> Engineer",
                        "<p>" + "d".repeat(1_000) + "</p>",
                        java.util.Collections.nCopies(20, "<b>" + "t".repeat(128) + "</b>"),
                        java.util.Collections.nCopies(20, "<i>" + "j".repeat(128) + "</i>"),
                        "<span>Berlin</span>\r\n<script>x</script>", 1L)
        ), false), new byte[32], codec);

        ArbeitnowJobScanService.ScannedJob scanned = service.scan(null).jobs().getFirst();

        assertEquals("Acme", scanned.company());
        assertEquals("Platform Engineer", scanned.title());
        assertEquals("Berlin", scanned.location());
        assertEquals("t".repeat(128), scanned.tags().getFirst());
        assertEquals("j".repeat(128), scanned.jobTypes().getFirst());
        assertEquals(20, scanned.tags().size());
        assertEquals(20, scanned.jobTypes().size());
        assertEquals(scanned.slug(), codec.verify(scanned.candidateToken()).slug());
    }

    @Test
    void decodesHtmlEntitiesBeforeRemovingEncodedMarkupAndBoundsPlainExcerpt() {
        String oversized = "x".repeat(1_010);
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("plain", "Acme", "Java", "&copy; &#169; &#xA9; &lt;script&gt;secret()&lt;/script&gt;&lt;style&gt;.x{}&lt;/style&gt;&lt;b&gt;Visible&lt;/b&gt; &bogus;\u0000 " + oversized,
                        List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false));

        String excerpt = service.scan(null).jobs().getFirst().descriptionExcerpt();

        assertTrue(excerpt.startsWith("© © © Visible &bogus;"));
        assertFalse(excerpt.contains("secret"));
        assertFalse(excerpt.contains(".x"));
        assertFalse(excerpt.contains("\u0000"));
        assertEquals(1_000, excerpt.length());
    }

    @Test
    void leavesDoubleEncodedMarkupEscapedAndNeutralizesMalformedResidualAngles() {
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("plain", "Acme", "Java",
                        "&amp;lt;script&amp;gt;double()&amp;lt;/script&amp;gt; &amp;lt;style&amp;gt;.x{}&amp;lt;/style&amp;gt; &amp;lt;b&amp;gt;Encoded visible&amp;lt;/b&amp;gt; \u0002Broken <script data-x='1' unfinished <<b>>Nested visible<</b>>",
                        List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false));

        String excerpt = service.scan(null).jobs().getFirst().descriptionExcerpt();

        assertEquals("&lt;script&gt;double()&lt;/script&gt; &lt;style&gt;.x{}&lt;/style&gt; &lt;b&gt;Encoded visible&lt;/b&gt; Broken Nested visible", excerpt);
        assertFalse(excerpt.contains("<"));
        assertFalse(excerpt.contains(">"));
        assertFalse(excerpt.contains("\u0002"));
    }

    @Test
    void rejectsInvalidConstructorKeysAndCursorOffsetsPastFilteredMatches() {
        assertThrows(NullPointerException.class, () -> new ArbeitnowJobScanService(null, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowJobScanService((_, _) -> page(List.of(), false), (byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowJobScanService((_, _) -> page(List.of(), false), new byte[31]));
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("one", "Acme", "Java", "Description", List.of(), List.of(), "Berlin", 1L)
        ), false), new byte[32]);
        String payload = "1:2:" + formerFingerprint();
        String protectedPayload = payload + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload, new byte[32]));
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(protectedPayload.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 1, cursor)));
    }

    @Test
    void mapsNullTimestampsAndFailsClosedWhenCursorMacCreationFails() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC), new byte[32]);
        ArbeitnowJobScanService normal = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("one", "Acme", "Java", "Description", List.of(), List.of(), "Berlin", null)), false), new byte[32], codec);
        assertNull(normal.scan(null).jobs().getFirst().postedAt());

        ArbeitnowJobScanService unavailableMac = new ArbeitnowJobScanService((_, _) -> page(List.of(), false), new byte[32], codec,
                () -> { throw new Exception("mac unavailable"); });
        assertThrows(IllegalStateException.class, () -> unavailableMac.scan(null));
    }

    @Test
    void rejectsAValidCursorWhenTheMutableUpstreamPageShrinksBelowItsOffset() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> calls.getAndIncrement() == 0
                ? page(List.of(
                job("first", "Acme", "Java", "Description", List.of(), List.of(), "Berlin", 1L),
                job("second", "Acme", "Java", "Description", List.of(), List.of(), "Berlin", 2L)), false)
                : page(List.of(), false), new byte[32]);
        ArbeitnowJobScanService.ScanRequest request = new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 1, null);

        String cursor = service.scan(request).nextCursor();

        assertThrows(IllegalArgumentException.class, () -> service.scan(withCursor(request, cursor)));
    }

    @Test
    void handlesNoMatchesNullUpstreamOptionalValuesAndFilterRejections() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC), new byte[32]);
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("null-values", null, null, null, null, null, null, null),
                job("not-remote", "Acme", "Java", "Description", List.of("Java"), List.of("full-time"), "Berlin", 1L)
        ), false), new byte[32], codec);
        ArbeitnowJobScanService.ScanResult none = service.scan(new ArbeitnowJobScanService.ScanRequest("missing", null, null, null, null, null, 1, 1, null));
        assertTrue(none.jobs().isEmpty());
        ArbeitnowJobScanService.ScanResult location = service.scan(new ArbeitnowJobScanService.ScanRequest(null, "missing", null, null, null, null, 1, 1, null));
        assertTrue(location.jobs().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, java.util.Arrays.asList((String) null), null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, List.of("x".repeat(129)), null, null, null)));
    }

    @Test
    void coversTerminalCursorAndRemainingFilterAndValidationSides() {
        AtomicInteger calls = new AtomicInteger();
        ArbeitnowJobScanService service = new ArbeitnowJobScanService((page, _) -> {
            calls.incrementAndGet();
            return page == 1
                    ? page(List.of(job("one", "Acme", "Java", "Description", null, null, "Berlin", 1L)), true)
                    : page(List.of(job("two", "Acme", "Java", "Description", List.of(), List.of(), "Berlin", 2L)), false);
        }, new byte[32]);
        ArbeitnowJobScanService.ScanRequest one = new ArbeitnowJobScanService.ScanRequest(" ", null, false, null, List.of(), List.of(), 1, 2, null);
        ArbeitnowJobScanService.ScanResult first = service.scan(one);
        assertNotNull(first.nextCursor());
        assertEquals("two", service.scan(withCursor(one, first.nextCursor())).jobs().getFirst().slug());
        assertEquals(2, calls.get());

        ArbeitnowJobScanService remoteFilter = new ArbeitnowJobScanService((_, _) -> page(List.of(
                new ArbeitnowJobBoardPort.UpstreamJob("not-remote", "Acme", "Java", "Description", false, "ignored", List.of(), List.of(), "Berlin", null)
        ), false));
        assertTrue(remoteFilter.scan(new ArbeitnowJobScanService.ScanRequest(null, null, true, null, null, null, 1, 1, null)).jobs().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowJobScanService((_, _) -> page(List.of(
                job(null, "Acme", "Java", "Description", List.of(), List.of(), "Berlin", 1L)), false)).scan(null));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, 1, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null,
                java.util.Collections.nCopies(11, "full-time"), 1, 1, null)));
        ArbeitnowJobScanService blanks = new ArbeitnowJobScanService((_, _) -> page(List.of(
                job("blank-values", "Acme", "Java", "Description", List.of(" "), List.of("\t"), "Berlin", 1L)), false));
        assertEquals(List.of(), blanks.scan(null).jobs().getFirst().tags());
        assertEquals(List.of(), blanks.scan(null).jobs().getFirst().jobTypes());
    }

    private static ArbeitnowJobScanService.ScanRequest withCursor(ArbeitnowJobScanService.ScanRequest request, String cursor) {
        return new ArbeitnowJobScanService.ScanRequest(request.query(), request.location(), request.remoteOnly(), request.visaSponsorship(), request.tags(), request.jobTypes(), request.limit(), request.maxPages(), cursor);
    }

    private static String mutate(String cursor) {
        byte[] bytes = Base64.getUrlDecoder().decode(cursor);
        bytes[0] = bytes[0] == '1' ? (byte) '2' : (byte) '1';
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String formerPublicCursor(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + ":" + sha256(payload + ":arbeitnow-v1")).getBytes(StandardCharsets.UTF_8));
    }

    private static String formerFingerprint() {
        return sha256("|".repeat(5));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] hmac(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static ArbeitnowJobBoardPort.Page page(List<ArbeitnowJobBoardPort.UpstreamJob> jobs, boolean more) {
        return new ArbeitnowJobBoardPort.Page(jobs, more);
    }

    private static ArbeitnowJobBoardPort.UpstreamJob job(String slug, String company, String title, String description,
                                                         List<String> tags, List<String> types, String location, Long createdAt) {
        return new ArbeitnowJobBoardPort.UpstreamJob(slug, company, title, description, true, "https://upstream.invalid/ignored", tags, types, location, createdAt);
    }
}

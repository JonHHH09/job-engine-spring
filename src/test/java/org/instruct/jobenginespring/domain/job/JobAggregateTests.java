package org.instruct.jobenginespring.domain.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobAggregateTests {

    private static final Instant NOW = Instant.parse("2026-07-09T12:00:00Z");
    private static final UUID JOB_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");

    @Test
    void rejectsMissingProvenance() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new JobAggregate(posting("text"), List.of(), null, null));

        assertEquals("job aggregate must contain exactly one provenance source", exception.getMessage());
    }

    @Test
    void rejectsMultipleProvenanceSources() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new JobAggregate(posting("text"), List.of(), linkIngestion(), textIngestion()));

        assertEquals("job aggregate must contain exactly one provenance source", exception.getMessage());
    }

    @Test
    void rejectsProvenanceThatDoesNotMatchSourceMethod() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new JobAggregate(posting("link"), List.of(), null, textIngestion()));

        assertEquals("job sourceMethod must match provenance source", exception.getMessage());
    }

    @Test
    void rejectsProvenanceForDifferentJobId() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new JobAggregate(
                        posting("text"),
                        List.of(),
                        null,
                        new JobTextIngestion(UUID.randomUUID(), UUID.randomUUID(), "paste", "hash", NOW)
                ));

        assertEquals("textIngestion jobId must match job id", exception.getMessage());
    }

    private static JobPosting posting(String sourceMethod) {
        return new JobPosting(
                JOB_ID,
                sourceMethod,
                "Example source",
                "Backend Engineer",
                "Example Corp",
                "Remote",
                "Build backend services",
                null,
                null,
                null,
                NOW,
                "fingerprint",
                NOW,
                NOW
        );
    }

    private static JobLinkIngestion linkIngestion() {
        return new JobLinkIngestion(UUID.randomUUID(), JOB_ID, "https://example.test/jobs/1", "https://example.test/jobs/1", NOW, 200, "Backend Engineer", NOW);
    }

    private static JobTextIngestion textIngestion() {
        return new JobTextIngestion(UUID.randomUUID(), JOB_ID, "paste", "hash", NOW);
    }
}

package org.instruct.jobenginespring.application.jobscan;

import org.instruct.jobenginespring.application.job.JobService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArbeitnowJobImportServiceTests {
    @Test
    void verifiesTokenBeforeDelegatingToTrustedJobPersistenceWithoutHttp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC);
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(clock, new byte[32]);
        JobService jobs = mock(JobService.class);
        ArbeitnowJobImportService service = new ArbeitnowJobImportService(codec, jobs);
        String token = codec.issue(candidate());
        when(jobs.importTrustedArbeitnowJob(any())).thenReturn(null);

        service.importCandidate(token);

        verify(jobs).importTrustedArbeitnowJob(new JobService.TrustedArbeitnowImportRequest(
                "https://arbeitnow.com/view/platform-engineer", "Acme", "Platform Engineer", "Berlin", "Build systems",
                List.of("Java", "Spring"), "full-time", true, Instant.parse("2026-07-20T00:00:00Z")));
    }

    @Test
    void rejectsInvalidTokenWithoutPersistence() {
        JobService jobs = mock(JobService.class);
        ArbeitnowJobImportService service = new ArbeitnowJobImportService(
                new ArbeitnowCandidateTokenCodec(Clock.systemUTC(), new byte[32]), jobs);

        assertThrows(IllegalArgumentException.class, () -> service.importCandidate("not-a-token"));
        verifyNoInteractions(jobs);
    }

    @Test
    void importsRemoteMetadataAndUsesNullEmploymentTypeForAnEmptySignedList() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC);
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(clock, new byte[32]);
        JobService jobs = mock(JobService.class);
        ArbeitnowJobImportService service = new ArbeitnowJobImportService(codec, jobs);
        ArbeitnowCandidateTokenCodec.Candidate candidate = new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", clock.instant(), clock.instant().plusSeconds(900), "remote-engineer",
                "https://arbeitnow.com/view/remote-engineer", "Acme", "Remote Engineer", "Remote", false,
                null, List.of(), List.of(), "Build resilient systems");

        service.importCandidate(codec.issue(candidate));

        verify(jobs).importTrustedArbeitnowJob(new JobService.TrustedArbeitnowImportRequest(
                "https://arbeitnow.com/view/remote-engineer", "Acme", "Remote Engineer", "Remote",
                "Build resilient systems", List.of(), null, false, null));
    }

    @Test
    void rejectsNullCollaboratorsAtConstruction() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(Clock.systemUTC(), new byte[32]);
        JobService jobs = mock(JobService.class);

        assertThrows(NullPointerException.class, () -> new ArbeitnowJobImportService(null, jobs));
        assertThrows(NullPointerException.class, () -> new ArbeitnowJobImportService(codec, null));
    }

    private static ArbeitnowCandidateTokenCodec.Candidate candidate() {
        return new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", Instant.parse("2026-07-27T10:00:00Z"), Instant.parse("2026-07-27T10:15:00Z"),
                "platform-engineer", "https://arbeitnow.com/view/platform-engineer", "Acme", "Platform Engineer", "Berlin", true,
                Instant.parse("2026-07-20T00:00:00Z"), List.of("Java", "Spring"), List.of("full-time"), "Build systems");
    }
}

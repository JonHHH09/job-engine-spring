package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobAnalysisService.AddJobFromAnalysisRequest;
import org.instruct.jobenginespring.application.job.JobAnalysisService.AnalyzeJobLinkRequest;
import org.instruct.jobenginespring.application.job.port.JobAnalysisRunRepository;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.port.JobPostingAnalysisPort;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobAnalysisRun;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobAnalysisServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-06T22:15:00Z");
    private static final UUID ANALYSIS_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-111111111111");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-cccc-dddd-111111111111");

    private final FakeAnalysisRunRepository analysisRepository = new FakeAnalysisRunRepository();
    private final FakeLinkFetcher fetcher = new FakeLinkFetcher();
    private final FakeAnalysisPort analysisPort = new FakeAnalysisPort();
    private final JobService jobService = mock(JobService.class);
    private final JobAnalysisService service = new JobAnalysisService(
            analysisRepository,
            fetcher,
            analysisPort,
            jobService,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void analyzeJobLinkStoresHermesResponseBeforeAnyJobWrite() {
        analysisPort.response = sampleAnalysisResponse();

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest(
                "https://Example.test/jobs/123?token=secret-value&utm_source=email#details"
        ));

        assertEquals("analysis_ready", result.status());
        assertEquals("https://example.test/jobs/123", result.normalizedUrl());
        assertEquals("SUCCEEDED", result.hermesStatus());
        assertEquals("VALID", result.validationStatus());
        assertEquals(List.of(), result.validationErrors());
        assertEquals("Platform Engineer", result.extractedFields().get("title"));
        assertNotNull(result.analysisRunId());
        JobAnalysisRun stored = analysisRepository.saved.get(result.analysisRunId());
        assertEquals("link", stored.sourceType());
        assertEquals("https://example.test/jobs/123", stored.originalUrl());
        assertEquals("https://example.test/jobs/123", stored.normalizedUrl());
        assertEquals("https://Example.test/jobs/123?token=secret-value&utm_source=email#details", fetcher.lastUrl);
        assertFalse(stored.inputJson().toString().contains("secret-value"));
        assertEquals("FETCHED", stored.fetchStatus());
        assertEquals(200, stored.httpStatus());
        assertEquals("Platform Engineer", stored.hermesResponseJson().get("title"));
        assertTrue(stored.inputJson().containsKey("boundedVisibleText"));
        assertNotNull(stored.inputSha256());
        assertNotNull(stored.hermesResponseSha256());
        verifyNoInteractions(jobService);
    }

    @Test
    void analyzeJobLinkSkipsProviderForFailedFetchContent() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://example.test/jobs/blocked",
                "404 Not Found",
                "Not found",
                404
        );

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest("https://example.test/jobs/blocked"));

        assertEquals("analysis_invalid", result.status());
        assertEquals("FAILED", result.fetchStatus());
        assertEquals("SKIPPED", result.hermesStatus());
        assertEquals("INVALID", result.validationStatus());
        assertEquals(List.of("job page fetch returned HTTP 404; provide pasted job text or a usable description"), result.validationErrors());
        assertEquals(0, analysisPort.calls);
        verifyNoInteractions(jobService);
        assertNull(analysisRepository.saved.get(result.analysisRunId()).hermesResponseJson());
    }

    @Test
    void analyzeJobLinkDoesNotCallProviderForBlockedFetchedContent() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://example.test/jobs/blocked",
                "Security Check - Example",
                "Additional Verification Required. Enable JavaScript and cookies to continue.",
                200
        );

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest("https://example.test/jobs/blocked"));

        assertEquals("analysis_invalid", result.status());
        assertEquals("SECURITY_CHECK", result.fetchStatus());
        assertEquals("SKIPPED", result.hermesStatus());
        assertEquals("INVALID", result.validationStatus());
        assertEquals(0, analysisPort.calls);
        assertTrue(result.validationErrors().contains("job page fetch returned security-check content; provide pasted job text or a usable description"));
    }

    @Test
    void analyzeJobLinkDoesNotCallProviderWhenFetcherReturnsNullContent() {
        fetcher.result = null;

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest("https://example.test/jobs/missing"));

        assertEquals("analysis_invalid", result.status());
        assertEquals("FAILED", result.fetchStatus());
        assertEquals("SKIPPED", result.hermesStatus());
        assertEquals("INVALID", result.validationStatus());
        assertEquals(0, analysisPort.calls);
        assertTrue(result.validationErrors().contains("job page fetch returned no content; provide pasted job text or a usable description"));
    }

    @Test
    void addJobFromAnalysisReadsStoredHermesResponseAndUpdatesRunWithJobId() {
        JobAnalysisRun analysisRun = storedValidRun(ANALYSIS_ID);
        analysisRepository.saved.put(ANALYSIS_ID, analysisRun);
        JobAggregate aggregate = new JobAggregate(samplePosting(), List.of(), null, null);
        when(jobService.addJobFromAnalyzedLink(any(JobService.AddJobFromAnalyzedLinkRequest.class)))
                .thenReturn(new JobService.AddJobResult("created_job", aggregate));

        JobAnalysisService.AddJobFromAnalysisResult result = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(ANALYSIS_ID));

        assertEquals(ANALYSIS_ID, result.analysisRunId());
        assertEquals("created_job", result.status());
        assertEquals(JOB_ID, result.jobResult().job().job().id());
        assertEquals(List.of(), result.validationErrors());
        JobAnalysisRun updated = analysisRepository.saved.get(ANALYSIS_ID);
        assertEquals(JOB_ID, updated.createdJobId());
        assertEquals("VALID", updated.validationStatus());
        verify(jobService).addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "Hermes analysis",
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Build and maintain Kubernetes developer platforms for product teams.",
                List.of("Java", "Kubernetes"),
                "3+ years",
                "Full-time",
                "Mid",
                Instant.parse("2026-07-01T00:00:00Z"),
                200,
                "Fetched Platform Engineer"
        ));
        assertEquals(0, analysisPort.calls);
    }

    @Test
    void addJobFromAnalysisRejectsStoredFailedBlockedOrSecurityCheckFetchProvenance() {
        UUID failedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-555555555555");
        UUID blockedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-666666666666");
        UUID securityCheckId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-777777777777");
        analysisRepository.saved.put(failedId, storedValidRunWithFetchProvenance(failedId, "FAILED", 502, "Bad Gateway"));
        analysisRepository.saved.put(blockedId, storedValidRunWithFetchProvenance(blockedId, "BLOCKED", 200, "Request blocked"));
        analysisRepository.saved.put(securityCheckId, storedValidRunWithFetchProvenance(securityCheckId, "SECURITY_CHECK", 200, "Security Check - Indeed"));

        JobAnalysisService.AddJobFromAnalysisResult failed = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(failedId));
        JobAnalysisService.AddJobFromAnalysisResult blocked = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(blockedId));
        JobAnalysisService.AddJobFromAnalysisResult securityCheck = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(securityCheckId));

        assertEquals("analysis_invalid", failed.status());
        assertTrue(failed.validationErrors().contains("job page fetch failed; provide pasted job text or a usable description"));
        assertEquals("analysis_invalid", blocked.status());
        assertTrue(blocked.validationErrors().contains("job page fetch returned blocked content; provide pasted job text or a usable description"));
        assertEquals("analysis_invalid", securityCheck.status());
        assertTrue(securityCheck.validationErrors().contains("job page fetch returned security-check content; provide pasted job text or a usable description"));
        verifyNoInteractions(jobService);
        assertEquals("INVALID", analysisRepository.saved.get(failedId).validationStatus());
        assertEquals("INVALID", analysisRepository.saved.get(blockedId).validationStatus());
        assertEquals("INVALID", analysisRepository.saved.get(securityCheckId).validationStatus());
    }

    @Test
    void weakStoredAnalysisDoesNotCreateJob() {
        Map<String, Object> weakResponse = new LinkedHashMap<>();
        weakResponse.put("title", "https://example.test/jobs/123");
        weakResponse.put("description", "Enable JavaScript");
        analysisRepository.saved.put(ANALYSIS_ID, new JobAnalysisRun(
                ANALYSIS_ID,
                "link",
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "FETCHED",
                200,
                "Example",
                "input-hash",
                Map.of("sourceMethod", "link"),
                "SUCCEEDED",
                weakResponse,
                "response-hash",
                "PENDING",
                List.of(),
                null,
                NOW,
                NOW
        ));

        JobAnalysisService.AddJobFromAnalysisResult result = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(ANALYSIS_ID));

        assertEquals("analysis_invalid", result.status());
        assertTrue(result.validationErrors().contains("title must be a real job title, not a URL"));
        assertTrue(result.validationErrors().contains("description must contain job-specific detail"));
        verifyNoInteractions(jobService);
        assertEquals("INVALID", analysisRepository.saved.get(ANALYSIS_ID).validationStatus());
    }

    @Test
    void missingAndShellStoredAnalysesDoNotCreateJobs() {
        analysisRepository.saved.put(ANALYSIS_ID, storedRunWithResponse(Map.of(
                "description", "This description is long enough but has no title."
        )));
        JobAnalysisService.AddJobFromAnalysisResult missingTitle = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(ANALYSIS_ID));
        assertEquals(List.of("title is required"), missingTitle.validationErrors());

        UUID missingDescriptionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-222222222222");
        analysisRepository.saved.put(missingDescriptionId, storedRunWithResponse(Map.of(
                "title", "Platform Engineer"
        )));
        JobAnalysisService.AddJobFromAnalysisResult missingDescription = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(missingDescriptionId));
        assertEquals(List.of("description is required"), missingDescription.validationErrors());

        UUID shellId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-333333333333");
        analysisRepository.saved.put(shellId, storedRunWithResponse(Map.of(
                "title", "Platform Engineer",
                "description", "This posting says JavaScript is required to view the app shell."
        )));
        JobAnalysisService.AddJobFromAnalysisResult shell = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(shellId));
        assertEquals(List.of("description looks like a bot/security check or JavaScript shell, not a job description"), shell.validationErrors());

        UUID botCheckId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-444444444444");
        analysisRepository.saved.put(botCheckId, storedRunWithResponse(Map.of(
                "title", "Software Engineer",
                "description", "Security Check - Indeed.com Additional Verification Required. Please enable JavaScript and cookies to continue."
        )));
        JobAnalysisService.AddJobFromAnalysisResult botCheck = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(botCheckId));
        assertEquals(List.of("description looks like a bot/security check or JavaScript shell, not a job description"), botCheck.validationErrors());
        verifyNoInteractions(jobService);
    }

    @Test
    void analysisReadyCanReturnInvalidValidationWithoutCreatingJob() {
        analysisPort.response = new JobPostingAnalysisPort.JobPostingAnalysisResponse(
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Enable JavaScript to view this app shell and continue loading the site.",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest("https://example.test/jobs/123"));

        assertEquals("analysis_invalid", result.status());
        assertEquals("INVALID", result.validationStatus());
        assertEquals(List.of("description looks like a bot/security check or JavaScript shell, not a job description"), result.validationErrors());
        assertEquals(List.of(), new JobPostingAnalysisPort.JobPostingAnalysisResponse(null, null, null, null, null, null, null, null, null, null, null).skills());
    }

    @Test
    void analysisPortRequestAndResultRecordsDefensivelyDefaultNullCollections() {
        JobPostingAnalysisPort.JobPostingAnalysisRequest request = new JobPostingAnalysisPort.JobPostingAnalysisRequest(null);
        assertEquals(Map.of(), request.inputJson());
        JobAnalysisService.AnalyzeJobLinkResult analyzeResult = new JobAnalysisService.AnalyzeJobLinkResult(
                ANALYSIS_ID,
                "analysis_failed",
                "https://example.test/jobs/123",
                "FETCHED",
                "FAILED",
                "INVALID",
                null,
                null
        );
        assertEquals(List.of(), analyzeResult.validationErrors());
        assertEquals(Map.of(), analyzeResult.extractedFields());
        JobAnalysisService.AddJobFromAnalysisResult addResult = new JobAnalysisService.AddJobFromAnalysisResult(ANALYSIS_ID, "analysis_invalid", null, null);
        assertEquals(List.of(), addResult.validationErrors());
    }

    @Test
    void invalidDatesAndNonStringSkillsAreIgnoredWhenCreatingJob() {
        analysisRepository.saved.put(ANALYSIS_ID, storedRunWithResponse(Map.of(
                "title", "Platform Engineer",
                "description", "Build internal developer platforms for product teams using Kubernetes.",
                "skills", List.of("Java", 42, " "),
                "postedDate", "not-an-instant"
        )));
        JobAggregate aggregate = new JobAggregate(samplePosting(), List.of(), null, null);
        when(jobService.addJobFromAnalyzedLink(any(JobService.AddJobFromAnalyzedLinkRequest.class)))
                .thenReturn(new JobService.AddJobResult("created_job", aggregate));

        JobAnalysisService.AddJobFromAnalysisResult result = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(ANALYSIS_ID));

        assertEquals("created_job", result.status());
        verify(jobService).addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "Hermes analysis",
                "Platform Engineer",
                null,
                null,
                "Build internal developer platforms for product teams using Kubernetes.",
                List.of("Java"),
                null,
                null,
                null,
                null,
                200,
                "Fetched Platform Engineer"
        ));
    }

    @Test
    void invalidUrlsAreRejected() {
        assertThrows(ApplicationException.class, () -> service.analyzeJobLink(new AnalyzeJobLinkRequest(null)));
        assertThrows(ApplicationException.class, () -> service.analyzeJobLink(new AnalyzeJobLinkRequest("mailto:test@example.test")));
        assertThrows(ApplicationException.class, () -> service.analyzeJobLink(new AnalyzeJobLinkRequest("https://exa mple.test/jobs")));
    }

    @Test
    void jobAnalysisRunRejectsMissingRequiredFieldsAndDefaultsCollections() {
        JobAnalysisRun run = new JobAnalysisRun(
                ANALYSIS_ID,
                "link",
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "FETCHED",
                200,
                null,
                "input-hash",
                Map.of("sourceMethod", "link"),
                "FAILED",
                null,
                null,
                "INVALID",
                null,
                null,
                NOW,
                NOW
        );
        assertEquals(List.of(), run.validationErrors());
        assertNull(run.hermesResponseJson());
        assertThrows(IllegalArgumentException.class, () -> new JobAnalysisRun(ANALYSIS_ID, " ", null, null, "FETCHED", null, null, "input-hash", Map.of(), "FAILED", null, null, "INVALID", List.of(), null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new JobAnalysisRun(ANALYSIS_ID, null, null, null, "FETCHED", null, null, "input-hash", Map.of(), "FAILED", null, null, "INVALID", List.of(), null, NOW, NOW));
    }

    @Test
    void unavailableAdapterReportsSafeProviderFailure() {
        var adapter = new org.instruct.jobenginespring.adapter.out.hermes.job.UnavailableJobPostingAnalysisAdapter();
        ApplicationException exception = assertThrows(ApplicationException.class, () -> adapter.analyze(new JobPostingAnalysisPort.JobPostingAnalysisRequest(Map.of())));
        assertEquals(Map.of("provider", "hermes-cli"), exception.details());
    }

    @Test
    void analysisProviderFailureIsPersistedAsFailedRun() {
        analysisPort.failure = new RuntimeException("provider down");

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest(
                "https://example.test/jobs/123"
        ));

        assertEquals("analysis_failed", result.status());
        assertEquals("FAILED", result.hermesStatus());
        assertEquals("INVALID", result.validationStatus());
        assertEquals(List.of("Hermes analysis provider failed or is not configured"), result.validationErrors());
        assertEquals("FAILED", analysisRepository.saved.get(result.analysisRunId()).hermesStatus());
    }

    @Test
    void persistenceFailureAfterProviderSuccessIsNotReportedAsProviderFailure() {
        analysisPort.response = sampleAnalysisResponse();
        analysisRepository.saveFailure = new RuntimeException("database unavailable");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.analyzeJobLink(new AnalyzeJobLinkRequest(
                "https://example.test/jobs/123"
        )));

        assertEquals("database unavailable", exception.getMessage());
        assertEquals(1, analysisPort.calls);
        assertTrue(analysisRepository.saved.isEmpty());
    }

    @Test
    void nullAnalysisResponseIsPersistedAsInvalidAnalysis() {
        analysisPort.response = null;

        JobAnalysisService.AnalyzeJobLinkResult result = service.analyzeJobLink(new AnalyzeJobLinkRequest("https://example.test"));

        assertEquals("analysis_invalid", result.status());
        assertEquals("FETCHED", result.fetchStatus());
        assertEquals("Hermes analysis response is missing", result.validationErrors().getFirst());
        assertEquals("https://example.test/", result.normalizedUrl());
    }

    @Test
    void nullStoredHermesResponseDoesNotCreateJob() {
        analysisRepository.saved.put(ANALYSIS_ID, new JobAnalysisRun(
                ANALYSIS_ID,
                "link",
                "https://example.test/jobs/123/",
                "https://example.test/jobs/123",
                "FETCHED",
                200,
                "Fetched Platform Engineer",
                "input-hash",
                Map.of("sourceMethod", "link"),
                "SUCCEEDED",
                null,
                null,
                "PENDING",
                List.of(),
                null,
                NOW,
                NOW
        ));

        JobAnalysisService.AddJobFromAnalysisResult result = service.addJobFromAnalysis(new AddJobFromAnalysisRequest(ANALYSIS_ID));

        assertEquals("analysis_invalid", result.status());
        assertEquals(List.of("Hermes analysis response is missing"), result.validationErrors());
        verifyNoInteractions(jobService);
    }

    @Test
    void addJobFromAnalysisRejectsNullRequest() {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.addJobFromAnalysis(null));
        assertEquals(Map.of("field", "request", "reason", "must not be null"), exception.details());
    }

    @Test
    void rejectsInvalidAnalysisRequests() {
        ApplicationException nullRequest = assertThrows(ApplicationException.class, () -> service.analyzeJobLink(null));
        assertEquals(Map.of("field", "request", "reason", "must not be null"), nullRequest.details());
        ApplicationException blankUrl = assertThrows(ApplicationException.class, () -> service.analyzeJobLink(new AnalyzeJobLinkRequest(" ")));
        assertEquals(Map.of("field", "url", "reason", "must not be blank"), blankUrl.details());
        ApplicationException missingRunId = assertThrows(ApplicationException.class, () -> service.addJobFromAnalysis(new AddJobFromAnalysisRequest(null)));
        assertEquals(Map.of("field", "analysisRunId", "reason", "must not be null"), missingRunId.details());
        assertThrows(JobAnalysisService.JobAnalysisRunNotFoundException.class, () -> service.addJobFromAnalysis(new AddJobFromAnalysisRequest(UUID.randomUUID())));
    }

    private static JobPostingAnalysisPort.JobPostingAnalysisResponse sampleAnalysisResponse() {
        return new JobPostingAnalysisPort.JobPostingAnalysisResponse(
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Build and maintain Kubernetes developer platforms for product teams.",
                List.of("Java", "Kubernetes"),
                "3+ years",
                "Full-time",
                "Mid",
                "2026-07-01T00:00:00Z",
                0.92,
                List.of("Fetched from public job page")
        );
    }

    private static JobAnalysisRun storedValidRun(UUID analysisRunId) {
        return new JobAnalysisRun(
                analysisRunId,
                "link",
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "FETCHED",
                200,
                "Fetched Platform Engineer",
                "input-hash",
                Map.of("sourceMethod", "link"),
                "SUCCEEDED",
                Map.of(
                        "title", "Platform Engineer",
                        "company", "Example Corp",
                        "location", "Remote",
                        "description", "Build and maintain Kubernetes developer platforms for product teams.",
                        "skills", List.of("Java", "Kubernetes"),
                        "experienceRequirement", "3+ years",
                        "employmentType", "Full-time",
                        "seniority", "Mid",
                        "postedDate", "2026-07-01T00:00:00Z"
                ),
                "response-hash",
                "PENDING",
                List.of(),
                null,
                NOW,
                NOW
        );
    }

    private static JobAnalysisRun storedValidRunWithFetchProvenance(UUID analysisRunId, String fetchStatus, Integer httpStatus, String fetchedTitle) {
        JobAnalysisRun valid = storedValidRun(analysisRunId);
        return new JobAnalysisRun(
                analysisRunId,
                valid.sourceType(),
                valid.originalUrl(),
                valid.normalizedUrl(),
                fetchStatus,
                httpStatus,
                fetchedTitle,
                valid.inputSha256(),
                valid.inputJson(),
                valid.hermesStatus(),
                valid.hermesResponseJson(),
                valid.hermesResponseSha256(),
                valid.validationStatus(),
                valid.validationErrors(),
                valid.createdJobId(),
                valid.createdAt(),
                valid.updatedAt()
        );
    }

    private static JobAnalysisRun storedRunWithResponse(Map<String, Object> responseJson) {
        return new JobAnalysisRun(
                ANALYSIS_ID,
                "link",
                "https://example.test/jobs/123",
                "https://example.test/jobs/123",
                "FETCHED",
                200,
                "Fetched Platform Engineer",
                "input-hash",
                Map.of("sourceMethod", "link"),
                "SUCCEEDED",
                responseJson,
                "response-hash",
                "PENDING",
                List.of(),
                null,
                NOW,
                NOW
        );
    }

    private static JobPosting samplePosting() {
        return new JobPosting(
                JOB_ID,
                "link",
                "Hermes analysis",
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Build and maintain Kubernetes developer platforms for product teams.",
                "3+ years",
                "Full-time",
                "Mid",
                Instant.parse("2026-07-01T00:00:00Z"),
                "fingerprint",
                NOW,
                NOW
        );
    }

    private static final class FakeAnalysisRunRepository implements JobAnalysisRunRepository {
        private final Map<UUID, JobAnalysisRun> saved = new LinkedHashMap<>();
        private RuntimeException saveFailure;

        @Override
        public JobAnalysisRun save(JobAnalysisRun analysisRun) {
            if (saveFailure != null) {
                throw saveFailure;
            }
            saved.put(analysisRun.id(), analysisRun);
            return analysisRun;
        }

        @Override
        public Optional<JobAnalysisRun> findById(UUID analysisRunId) {
            return Optional.ofNullable(saved.get(analysisRunId));
        }

        @Override
        public JobAnalysisRun update(JobAnalysisRun analysisRun) {
            saved.put(analysisRun.id(), analysisRun);
            return analysisRun;
        }
    }

    private static final class FakeLinkFetcher implements JobLinkContentFetcher {
        private JobLinkFetchResult result = new JobLinkFetchResult(
                "https://example.test/jobs/123",
                "Fetched Platform Engineer",
                "Fetched public page text for a platform engineering role using Java and Kubernetes.",
                200
        );
        private String lastUrl;

        @Override
        public JobLinkFetchResult fetch(String url) {
            lastUrl = url;
            return result;
        }
    }

    private static final class FakeAnalysisPort implements JobPostingAnalysisPort {
        private JobPostingAnalysisResponse response = sampleAnalysisResponse();
        private RuntimeException failure;
        private int calls;

        @Override
        public JobPostingAnalysisResponse analyze(JobPostingAnalysisRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}

package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobService.AddJobFromLinkRequest;
import org.instruct.jobenginespring.application.job.JobService.AddJobFromTextRequest;
import org.instruct.jobenginespring.application.job.JobService.AddJobResult;
import org.instruct.jobenginespring.application.job.JobService.JobSearchRequest;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-06T20:15:00Z");

    private final FakeJobRepository repository = new FakeJobRepository();
    private final FakeJobLinkContentFetcher fetcher = new FakeJobLinkContentFetcher();
    private final JobService service = new JobService(repository, fetcher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void addJobFromTextDerivesFieldsAndRequiredSkills() {
        AddJobResult result = service.addJobFromText(new AddJobFromTextRequest(
                "Senior Java Developer\nSkills: Java, Spring Boot; PostgreSQL\nExperience: 5+ years building APIs",
                "manual paste",
                null,
                " Example Corp ",
                " Montreal ",
                null,
                List.of("Java", "java", "MCP"),
                null,
                " Full-time ",
                " Senior ",
                NOW
        ));

        assertEquals("created_job", result.status());
        JobAggregate aggregate = result.job();
        assertNotNull(aggregate.job().id());
        assertEquals("text", aggregate.job().sourceMethod());
        assertEquals("Senior Java Developer", aggregate.job().title());
        assertEquals("Example Corp", aggregate.job().company());
        assertEquals("Montreal", aggregate.job().location());
        assertEquals("5+ years building APIs", aggregate.job().experienceRequirement());
        assertEquals("Full-time", aggregate.job().employmentType());
        assertEquals("Senior", aggregate.job().seniority());
        assertEquals(List.of("java", "mcp", "spring boot", "postgresql"), aggregate.skills().stream()
                .map(skill -> skill.normalizedSkill())
                .toList());
        assertNotNull(aggregate.textIngestion());
        assertEquals("manual paste", aggregate.textIngestion().sourceLabel());
    }

    @Test
    void addJobFromTextReusesExistingJobForSameSourceText() {
        AddJobFromTextRequest request = new AddJobFromTextRequest(
                "Backend Developer\nSkills: Java",
                "paste",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        AddJobResult first = service.addJobFromText(request);
        AddJobResult second = service.addJobFromText(request);

        assertEquals("created_job", first.status());
        assertEquals("reused_existing_job", second.status());
        assertEquals(first.job().job().id(), second.job().job().id());
        assertEquals(1, repository.listJobs().size());
    }

    @Test
    void addJobFromTextReusesExistingJobForSameCanonicalFingerprint() {
        AddJobResult first = service.addJobFromText(new AddJobFromTextRequest(
                "Source text one",
                "paste",
                "Backend Developer",
                "Example Corp",
                "Remote",
                "Build APIs",
                null,
                null,
                null,
                null,
                null
        ));

        AddJobResult second = service.addJobFromText(new AddJobFromTextRequest(
                "Different source text",
                "paste",
                "Backend Developer",
                "Example Corp",
                "Remote",
                "Build APIs",
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals("created_job", first.status());
        assertEquals("reused_existing_job", second.status());
        assertEquals(first.job().job().id(), second.job().job().id());
    }

    @Test
    void addJobFromTextReportsReuseWhenRepositoryReturnsRaceWinner() {
        AddJobResult first = service.addJobFromText(new AddJobFromTextRequest(
                "Backend Developer",
                "paste",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        repository.saveOverride = first.job();

        AddJobResult second = service.addJobFromText(new AddJobFromTextRequest(
                "Different text after race",
                "paste",
                "Race loser",
                null,
                null,
                "Different description after race",
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals("reused_existing_job", second.status());
        assertEquals(first.job().job().id(), second.job().job().id());
    }

    @Test
    void addJobFromLinkFetchesPageContentAndReusesNormalizedUrl() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://Example.test/jobs/123?ref=abc",
                "Platform Engineer",
                "Build cloud systems. Skills: Kubernetes, Java",
                200
        );
        AddJobFromLinkRequest request = new AddJobFromLinkRequest(
                "https://Example.test/jobs/123?ref=abc#section",
                "Example ATS",
                null,
                "Example Corp",
                "Remote",
                null,
                null,
                "3+ years with platform engineering",
                null,
                null,
                null
        );

        AddJobResult first = service.addJobFromLink(request);
        AddJobResult second = service.addJobFromLink(request);

        assertEquals("created_job", first.status());
        assertEquals("reused_existing_job", second.status());
        assertEquals("link", first.job().job().sourceMethod());
        assertEquals("Platform Engineer", first.job().job().title());
        assertEquals("https://example.test/jobs/123?ref=abc", first.job().linkIngestion().normalizedUrl());
        assertEquals(200, first.job().linkIngestion().httpStatus());
        assertEquals(List.of("kubernetes", "java"), first.job().skills().stream().map(skill -> skill.normalizedSkill()).toList());
    }

    @Test
    void addJobFromLinkRejectsBlockedFetchWithoutExplicitDescription() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Security Check - Indeed.com",
                "Security Check - Indeed.com Additional Verification Required Please enable JavaScript and cookies to continue",
                403
        );

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Indeed",
                "Software Engineer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(Map.of("field", "fetchedContent", "reason", "job page fetch returned HTTP 403; provide pasted job text or a usable description"), exception.details());
        assertEquals(List.of(), repository.listJobs());
    }

    @Test
    void addJobFromLinkRejectsSecurityCheckContentEvenWithSuccessfulHttpStatus() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Security Check - Indeed.com",
                "Additional Verification Required. Return home. Enable JavaScript and cookies to continue.",
                200
        );

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Indeed",
                "Software Engineer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )));

        assertEquals(Map.of("field", "fetchedContent", "reason", "job page fetch returned blocked/security-check content; provide pasted job text or a usable description"), exception.details());
        assertEquals(List.of(), repository.listJobs());
    }

    @Test
    void addJobFromLinkAllowsManualDescriptionWhenFetchIsBlocked() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Security Check - Indeed.com",
                "Security Check - Indeed.com Additional Verification Required Please enable JavaScript and cookies to continue",
                403
        );

        AddJobResult result = service.addJobFromLink(new AddJobFromLinkRequest(
                "https://ca.indeed.com/viewjob?jk=d285cc95895a79c6&from=appshareandroid",
                "Indeed",
                "Software Engineer",
                "Example Corp",
                "Remote",
                "Build production software and collaborate with product teams. Skills: Java, Spring Boot",
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals("created_job", result.status());
        assertEquals("Software Engineer", result.job().job().title());
        assertEquals("Build production software and collaborate with product teams. Skills: Java, Spring Boot", result.job().job().description());
        assertEquals(403, result.job().linkIngestion().httpStatus());
    }

    @Test
    void addJobFromAnalyzedLinkUsesStoredAnalysisFieldsWithoutFetching() {
        AddJobResult result = service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://Example.test/jobs/456#details",
                "https://example.test/jobs/456",
                "Hermes analysis",
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Build cloud platforms for internal developer teams. Skills: Kubernetes, Java",
                List.of("Kubernetes", "Java"),
                "4+ years",
                "Full-time",
                "Senior",
                NOW,
                200,
                "Fetched Title"
        ));

        assertEquals("created_job", result.status());
        assertEquals("Platform Engineer", result.job().job().title());
        assertEquals("Hermes analysis", result.job().job().sourceLabel());
        assertEquals("https://example.test/jobs/456", result.job().linkIngestion().normalizedUrl());
        assertEquals("Fetched Title", result.job().linkIngestion().sourceTitle());
        assertEquals(200, result.job().linkIngestion().httpStatus());
        assertEquals(List.of("kubernetes", "java"), result.job().skills().stream().map(JobSkill::normalizedSkill).toList());
        assertEquals(0, fetcher.calls);

        AddJobResult reused = service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://Example.test/jobs/456#details",
                "https://example.test/jobs/456",
                "Hermes analysis",
                "Platform Engineer",
                "Example Corp",
                "Remote",
                "Build cloud platforms for internal developer teams. Skills: Kubernetes, Java",
                List.of("Kubernetes", "Java"),
                "4+ years",
                "Full-time",
                "Senior",
                NOW,
                200,
                "Fetched Title"
        ));
        assertEquals("reused_existing_job", reused.status());
    }

    @Test
    void searchJobsReturnsDeterministicEvidence() {
        service.addJobFromText(new AddJobFromTextRequest(
                "Java Backend Developer\nSkills: Java, Spring Boot",
                null,
                null,
                "Example Corp",
                "Remote",
                null,
                null,
                null,
                null,
                null,
                null
        ));
        service.addJobFromText(new AddJobFromTextRequest(
                "Data Analyst\nSkills: SQL, Tableau",
                null,
                null,
                "Data Corp",
                "Montreal",
                null,
                null,
                null,
                null,
                null,
                null
        ));

        JobService.JobSearchResult result = service.searchJobs(new JobSearchRequest("java spring", 10));

        assertEquals(List.of("java", "spring"), result.queryTokens());
        assertEquals(1, result.totalMatches());
        assertEquals("Java Backend Developer", result.jobs().getFirst().job().title());
        assertTrue(result.jobs().getFirst().matchedFields().contains("job.title"));
        assertTrue(result.jobs().getFirst().matchedFields().contains("job.skills"));
    }

    @Test
    void searchJobsUsesStableTieBreakers() {
        service.addJobFromText(new AddJobFromTextRequest("Developer APIs", null, "Developer", "Beta", null, null, null, null, null, null, null));
        service.addJobFromText(new AddJobFromTextRequest("Developer Data", null, "Developer", "Alpha", null, null, null, null, null, null, null));

        JobService.JobSearchResult result = service.searchJobs(new JobSearchRequest("developer", 10));

        assertEquals(2, result.totalMatches());
        assertEquals(List.of("Developer", "Developer"), result.jobs().stream().map(match -> match.job().title()).toList());
    }

    @Test
    void listAndGetJobsDelegateToRepository() {
        AddJobResult created = service.addJobFromText(new AddJobFromTextRequest(
                "Backend Developer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals(List.of(created.job().job()), service.listJobs());
        assertEquals(Optional.of(created.job()), service.getJob(created.job().job().id()));
        assertThrows(NullPointerException.class, () -> service.getJob(null));
    }

    @Test
    void rejectsInvalidRequestsBeforePersistence() {
        assertInvalidTextRequest(null, "request", "must not be null");
        assertInvalidTextRequest(new AddJobFromTextRequest(null, null, null, null, null, null, null, null, null, null, null), "text", "must not be blank");
        assertInvalidTextRequest(new AddJobFromTextRequest(" ", null, null, null, null, null, null, null, null, null, null), "text", "must not be blank");
        ApplicationException linkException = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "not-a-url", null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must be an absolute http(s) URL"), linkException.details());
        ApplicationException nullLinkException = assertThrows(ApplicationException.class, () -> service.addJobFromLink(null));
        assertEquals(Map.of("field", "request", "reason", "must not be null"), nullLinkException.details());
        ApplicationException blankLinkException = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                " ", null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must not be blank"), blankLinkException.details());
        ApplicationException nullUrlException = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                null, null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must not be blank"), nullUrlException.details());
        ApplicationException uriSyntaxException = assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "https://exa mple.test/jobs", null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must be a valid absolute http(s) URL"), uriSyntaxException.details());
        ApplicationException nullAnalyzedRequest = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(null));
        assertEquals(Map.of("field", "request", "reason", "must not be null"), nullAnalyzedRequest.details());
        ApplicationException nullAnalyzedUrl = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                null, "https://example.test/jobs/1", null, "Title", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must not be blank"), nullAnalyzedUrl.details());
        ApplicationException blankAnalyzedUrl = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                " ", "https://example.test/jobs/1", null, "Title", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must not be blank"), blankAnalyzedUrl.details());
        ApplicationException nullNormalizedUrl = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", null, null, "Title", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "normalizedUrl", "reason", "must not be blank"), nullNormalizedUrl.details());
        ApplicationException blankNormalizedUrl = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", " ", null, "Title", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "normalizedUrl", "reason", "must not be blank"), blankNormalizedUrl.details());
        ApplicationException blankAnalyzedTitle = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", "https://example.test/jobs/1", null, " ", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "title", "reason", "must not be blank"), blankAnalyzedTitle.details());
        ApplicationException nullAnalyzedTitle = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", "https://example.test/jobs/1", null, null, null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "title", "reason", "must not be blank"), nullAnalyzedTitle.details());
        ApplicationException blankAnalyzedDescription = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", "https://example.test/jobs/1", null, "Title", null, null, " ", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "description", "reason", "must not be blank"), blankAnalyzedDescription.details());
        ApplicationException nullAnalyzedDescription = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", "https://example.test/jobs/1", null, "Title", null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "description", "reason", "must not be blank"), nullAnalyzedDescription.details());
        ApplicationException badAnalyzedNormalizedUrl = assertThrows(ApplicationException.class, () -> service.addJobFromAnalyzedLink(new JobService.AddJobFromAnalyzedLinkRequest(
                "https://example.test/jobs/1", "ftp://example.test/jobs/1", null, "Title", null, null, "Description long enough", null, null, null, null, null, null, null
        )));
        assertEquals(Map.of("field", "url", "reason", "must be an absolute http(s) URL"), badAnalyzedNormalizedUrl.details());
        ApplicationException nullSearchException = assertThrows(ApplicationException.class, () -> service.searchJobs(null));
        assertEquals(Map.of("field", "request", "reason", "must not be null"), nullSearchException.details());
        ApplicationException nullQuerySearchException = assertThrows(ApplicationException.class, () -> service.searchJobs(new JobSearchRequest(null, 10)));
        assertEquals(Map.of("field", "query", "reason", "must not be blank"), nullQuerySearchException.details());
        ApplicationException searchException = assertThrows(ApplicationException.class, () -> service.searchJobs(new JobSearchRequest(" ", 10)));
        assertEquals(Map.of("field", "query", "reason", "must not be blank"), searchException.details());
        ApplicationException punctuationSearchException = assertThrows(ApplicationException.class, () -> service.searchJobs(new JobSearchRequest("!!!", 10)));
        assertEquals(Map.of("field", "query", "reason", "must contain searchable text"), punctuationSearchException.details());
        ApplicationException limitSearchException = assertThrows(ApplicationException.class, () -> service.searchJobs(new JobSearchRequest("java", 101)));
        assertEquals(Map.of("field", "limit", "reason", "must be between 1 and 100"), limitSearchException.details());
        ApplicationException lowLimitSearchException = assertThrows(ApplicationException.class, () -> service.searchJobs(new JobSearchRequest("java", 0)));
        assertEquals(Map.of("field", "limit", "reason", "must be between 1 and 100"), lowLimitSearchException.details());
        assertEquals(0, service.searchJobs(new JobSearchRequest("java", null)).totalMatches());
        assertEquals(List.of(), repository.listJobs());
    }

    @Test
    void normalizesUrlByRemovingTrailingPathSlashAndFragment() {
        fetcher.result = new JobLinkContentFetcher.JobLinkFetchResult(
                "https://example.test/jobs/123/",
                "Platform Engineer",
                "Build platforms",
                200
        );

        AddJobResult result = service.addJobFromLink(new AddJobFromLinkRequest(
                "https://example.test/jobs/123/#details",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertEquals("https://example.test/jobs/123", result.job().linkIngestion().normalizedUrl());
    }

    @Test
    void domainJobRecordsRejectInvalidRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new JobPosting(
                UUID.randomUUID(), " ", null, "Title", null, null, "Description", null, null, null, null, "fingerprint", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobSkill(
                UUID.randomUUID(), UUID.randomUUID(), " ", "java", true, 0, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobSkill(
                UUID.randomUUID(), UUID.randomUUID(), "Java", "java", true, -1, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobLinkIngestion(
                UUID.randomUUID(), UUID.randomUUID(), " ", "https://example.test", NOW, 200, null, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobTextIngestion(
                UUID.randomUUID(), UUID.randomUUID(), null, " ", NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobPosting(
                UUID.randomUUID(), "text", null, null, null, null, "Description", null, null, null, null, "fingerprint", NOW, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobSkill(
                UUID.randomUUID(), UUID.randomUUID(), "Java", null, true, 0, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobLinkIngestion(
                UUID.randomUUID(), UUID.randomUUID(), "https://example.test", null, NOW, 200, null, NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new JobTextIngestion(
                UUID.randomUUID(), UUID.randomUUID(), null, null, NOW
        ));
        assertEquals(List.of(), new JobAggregate(new JobPosting(
                UUID.randomUUID(), "text", null, "Title", null, null, "Description", null, null, null, null, "fingerprint", NOW, NOW
        ), null, null, null).skills());
        assertEquals(List.of(), new JobService.JobSearchResult("java", null, 0, null).queryTokens());
        assertEquals(List.of(), new JobService.JobSearchResult("java", null, 0, null).jobs());
        JobPosting posting = new JobPosting(
                UUID.randomUUID(), "text", null, "Title", null, null, "Description", null, null, null, null, "fingerprint-2", NOW, NOW
        );
        assertEquals(List.of(), new JobService.JobSearchMatch(posting, null, 0, null).skills());
        assertEquals(List.of(), new JobService.JobSearchMatch(posting, null, 0, null).matchedFields());
    }

    @Test
    void privateTextExtractionHelpersHandleBlankInputs() throws Exception {
        Method extractSkills = JobService.class.getDeclaredMethod("extractSkills", String.class);
        Method extractExperience = JobService.class.getDeclaredMethod("extractExperience", String.class);
        Method mergeSkills = JobService.class.getDeclaredMethod("mergeSkills", List.class, List.class);
        Method splitSkillList = JobService.class.getDeclaredMethod("splitSkillList", String.class);
        Method scoreText = JobService.class.getDeclaredMethod("scoreText", List.class, String.class, String.class, int.class, Set.class);
        Method normalizeUrl = JobService.class.getDeclaredMethod("normalizeUrl", String.class);
        Method cleanToNull = JobService.class.getDeclaredMethod("cleanToNull", String.class);
        Method firstPresent = JobService.class.getDeclaredMethod("firstPresent", String[].class);
        Method normalizedKey = JobService.class.getDeclaredMethod("normalizedKey", String.class);
        Method tokens = JobService.class.getDeclaredMethod("tokens", String.class);
        extractSkills.setAccessible(true);
        extractExperience.setAccessible(true);
        mergeSkills.setAccessible(true);
        splitSkillList.setAccessible(true);
        scoreText.setAccessible(true);
        normalizeUrl.setAccessible(true);
        cleanToNull.setAccessible(true);
        firstPresent.setAccessible(true);
        normalizedKey.setAccessible(true);
        tokens.setAccessible(true);

        assertEquals(List.of(), extractSkills.invoke(null, " "));
        assertEquals(List.of(), extractSkills.invoke(null, new Object[]{null}));
        assertEquals(List.of(), extractSkills.invoke(null, "No explicit skills here"));
        assertEquals(null, extractExperience.invoke(null, " "));
        assertEquals(null, extractExperience.invoke(null, new Object[]{null}));
        assertEquals(null, extractExperience.invoke(null, "No explicit experience here"));
        assertEquals(List.of("Java"), mergeSkills.invoke(null, Arrays.asList(null, " ", "Java"), null));
        assertEquals(List.of("SQL"), mergeSkills.invoke(null, null, List.of("SQL")));
        assertEquals(List.of(), mergeSkills.invoke(null, null, null));
        assertEquals(List.of("Java"), mergeSkills.invoke(null, List.of("Java", " java "), List.of("JAVA")));
        assertEquals(List.of(), splitSkillList.invoke(null, new Object[]{null}));
        assertEquals(List.of(), splitSkillList.invoke(null, " "));
        assertEquals(List.of(), splitSkillList.invoke(null, ",,,"));
        assertEquals(List.of("Java"), splitSkillList.invoke(null, " ,Java"));
        Set<String> matchedFields = new LinkedHashSet<>();
        assertEquals(0, scoreText.invoke(null, List.of("java"), "field", null, 2, matchedFields));
        assertEquals(2, scoreText.invoke(null, List.of("java"), "field", "javascript", 2, matchedFields));
        assertEquals(2, scoreText.invoke(null, List.of("javascript"), "field", "java", 2, matchedFields));
        assertEquals("https://example.test/", normalizeUrl.invoke(null, "HTTPS://EXAMPLE.TEST"));
        assertEquals("http://example.test/jobs", normalizeUrl.invoke(null, "HTTP://EXAMPLE.TEST/jobs/"));
        assertEquals("https://example.test/jobs?ref=1", normalizeUrl.invoke(null, "https://example.test/jobs?ref=1#frag"));
        assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "example.test/job", null, null, null, null, null, null, null, null, null, null
        )));
        assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "ftp://example.test/job", null, null, null, null, null, null, null, null, null, null
        )));
        assertThrows(ApplicationException.class, () -> service.addJobFromLink(new AddJobFromLinkRequest(
                "https:/missing-host", null, null, null, null, null, null, null, null, null, null
        )));
        assertEquals(null, cleanToNull.invoke(null, new Object[]{null}));
        assertEquals(null, cleanToNull.invoke(null, ""));
        assertEquals(null, firstPresent.invoke(null, (Object) new String[]{null, " "}));
        assertEquals("", normalizedKey.invoke(null, new Object[]{null}));
        assertEquals(List.of(), tokens.invoke(null, " "));
        assertEquals(List.of("java"), tokens.invoke(null, " java "));
        assertEquals(List.of("java", "spring"), tokens.invoke(null, "---java---spring---"));
        AddJobResult derivedTitle = service.addJobFromText(new AddJobFromTextRequest(
                "X".repeat(161) + "\nShort title",
                null,
                null,
                "Long Title Corp",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        assertEquals("Short title", derivedTitle.job().job().title());
    }

    private void assertInvalidTextRequest(AddJobFromTextRequest request, String field, String reason) {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.addJobFromText(request));
        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("Invalid job request", exception.safeMessage());
        assertEquals(Map.of("field", field, "reason", reason), exception.details());
    }

    private static final class FakeJobLinkContentFetcher implements JobLinkContentFetcher {
        private JobLinkFetchResult result = new JobLinkFetchResult("https://example.test", "Fetched Job", "Fetched description", 200);
        private int calls;

        @Override
        public JobLinkFetchResult fetch(String url) {
            calls++;
            return result;
        }
    }

    private static final class FakeJobRepository implements JobRepository {
        private final Map<UUID, JobAggregate> aggregates = new LinkedHashMap<>();
        private JobAggregate saveOverride;

        @Override
        public List<JobPosting> listJobs() {
            return aggregates.values().stream().map(JobAggregate::job).toList();
        }

        @Override
        public Optional<JobAggregate> findJobAggregate(UUID jobId) {
            return Optional.ofNullable(aggregates.get(jobId));
        }

        @Override
        public Optional<JobAggregate> findByCanonicalFingerprint(String canonicalFingerprint) {
            return aggregates.values().stream()
                    .filter(aggregate -> aggregate.job().canonicalFingerprint().equals(canonicalFingerprint))
                    .findFirst();
        }

        @Override
        public Optional<JobAggregate> findByNormalizedSourceUrl(String normalizedUrl) {
            return aggregates.values().stream()
                    .filter(aggregate -> aggregate.linkIngestion() != null)
                    .filter(aggregate -> aggregate.linkIngestion().normalizedUrl().equals(normalizedUrl))
                    .findFirst();
        }

        @Override
        public Optional<JobAggregate> findByInputTextHash(String inputTextHash) {
            return aggregates.values().stream()
                    .filter(aggregate -> aggregate.textIngestion() != null)
                    .filter(aggregate -> aggregate.textIngestion().inputTextHash().equals(inputTextHash))
                    .findFirst();
        }

        @Override
        public JobAggregate saveJobAggregate(JobAggregate aggregate) {
            if (saveOverride != null) {
                JobAggregate override = saveOverride;
                saveOverride = null;
                return override;
            }
            aggregates.put(aggregate.job().id(), aggregate);
            return aggregate;
        }
    }
}

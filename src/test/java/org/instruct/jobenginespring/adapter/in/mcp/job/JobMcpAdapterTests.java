package org.instruct.jobenginespring.adapter.in.mcp.job;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobAnalysisService;
import org.instruct.jobenginespring.application.job.JobAnalysisService.AddJobFromAnalysisResult;
import org.instruct.jobenginespring.application.job.JobAnalysisService.AnalyzeJobLinkResult;
import org.instruct.jobenginespring.application.job.JobService;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.job.JobService.AddJobResult;
import org.instruct.jobenginespring.application.job.JobService.DeleteJobResult;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobMcpAdapterTests {

    private static final UUID JOB_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-07-06T20:45:00Z");
    private final JobService jobService = mock(JobService.class);
    private final JobAnalysisService jobAnalysisService = mock(JobAnalysisService.class);
    private final JobMcpAdapter adapter = new JobMcpAdapter(jobService, jobAnalysisService);

    @Test
    void exposesStableJobToolNames() {
        Set<String> toolNames = Arrays.stream(JobMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "list_jobs",
                "get_job",
                "search_jobs",
                "update_job",
                "delete_job",
                "add_job_from_text",
                "add_job_from_link",
                "analyze_job_link",
                "add_job_from_analysis"
        ), toolNames);
    }

    @Test
    void listJobsDelegatesToServiceWithObjectWrapper() {
        JobPosting posting = samplePosting();
        when(jobService.listJobs(1, null)).thenReturn(new Page<>(List.of(posting), "cursor"));

        CallToolResult result = adapter.listJobs(new JobMcpAdapter.ListRequest(1, null));

        assertFalse(result.isError());
        JobMcpAdapter.ListJobsResult content = assertInstanceOf(JobMcpAdapter.ListJobsResult.class, result.structuredContent());
        assertEquals(List.of(posting), content.jobs());
        assertEquals("cursor", content.nextCursor());
        assertEquals(List.of(), new JobMcpAdapter.ListJobsResult(null, null).jobs());
        verify(jobService).listJobs(1, null);
    }

    @Test
    void getJobReturnsExistingAggregateAndSanitizedMissingError() {
        JobAggregate aggregate = sampleAggregate();
        when(jobService.getJob(JOB_ID)).thenReturn(Optional.of(aggregate));

        CallToolResult found = adapter.getJob(JOB_ID);

        assertFalse(found.isError());
        assertEquals(aggregate, found.structuredContent());
        verify(jobService).getJob(JOB_ID);

        UUID missingId = UUID.fromString("bbbbbbbb-1111-1111-1111-bbbbbbbbbbbb");
        when(jobService.getJob(missingId)).thenReturn(Optional.empty());

        ApplicationErrorResponse response = assertErrorResponse(adapter.getJob(missingId));

        assertEquals("not_found", response.code());
        assertEquals("Job not found: " + missingId, response.message());
        assertEquals(Map.of("resource", "job", "jobId", missingId.toString()), response.details());
    }

    @Test
    void addAndSearchToolsDelegateToService() {
        JobMcpAdapter.AddJobFromTextRequest textRequest = new JobMcpAdapter.AddJobFromTextRequest("Java Developer", null, null, null, null, null, null, null, null, null, null);
        JobMcpAdapter.AddJobFromLinkRequest linkRequest = new JobMcpAdapter.AddJobFromLinkRequest("https://example.test/jobs/1", null, null, null, null, null, null, null, null, null, null);
        JobMcpAdapter.JobSearchRequest searchRequest = new JobMcpAdapter.JobSearchRequest("java", 5);
        JobService.AddJobFromTextRequest serviceTextRequest = textRequest.toServiceRequest();
        JobService.AddJobFromLinkRequest serviceLinkRequest = linkRequest.toServiceRequest();
        JobService.JobSearchRequest serviceSearchRequest = searchRequest.toServiceRequest();
        AddJobResult addResult = new AddJobResult("created_job", sampleAggregate());
        JobService.JobSearchResult searchResult = new JobService.JobSearchResult(
                "java", List.of("java"), 1, 1, false, 0, List.of());
        when(jobService.addJobFromText(serviceTextRequest)).thenReturn(addResult);
        when(jobService.addJobFromLink(serviceLinkRequest)).thenReturn(addResult);
        when(jobService.searchJobs(serviceSearchRequest)).thenReturn(searchResult);

        assertEquals(addResult, adapter.addJobFromText(textRequest).structuredContent());
        assertEquals(addResult, adapter.addJobFromLink(linkRequest).structuredContent());
        assertEquals(searchResult, adapter.searchJobs(searchRequest).structuredContent());
        verify(jobService).addJobFromText(serviceTextRequest);
        verify(jobService).addJobFromLink(serviceLinkRequest);
        verify(jobService).searchJobs(serviceSearchRequest);
    }

    @Test
    void updateAndDeleteToolsDelegateToService() {
        JobMcpAdapter.UpdateJobRequest updateRequest = new JobMcpAdapter.UpdateJobRequest(
                JOB_ID,
                "manual paste",
                "Senior Java Developer",
                "Example Corp",
                "Montreal",
                "Build backend services",
                List.of("Java", "Spring Boot"),
                "5+ years",
                "Full-time",
                "Senior",
                NOW
        );
        JobService.UpdateJobRequest serviceUpdateRequest = updateRequest.toServiceRequest();
        JobAggregate updated = new JobAggregate(samplePosting(), List.of(sampleSkill()), null, sampleTextIngestion());
        DeleteJobResult deleteResult = new DeleteJobResult(JOB_ID, true);
        when(jobService.updateJob(serviceUpdateRequest)).thenReturn(updated);
        when(jobService.deleteJob(JOB_ID)).thenReturn(deleteResult);

        assertEquals(updated, adapter.updateJob(updateRequest).structuredContent());
        assertEquals(deleteResult, adapter.deleteJob(JOB_ID).structuredContent());
        verify(jobService).updateJob(serviceUpdateRequest);
        verify(jobService).deleteJob(JOB_ID);
    }

    @Test
    void updateJobRequestMapsFieldsToServiceRequest() {
        JobMcpAdapter.UpdateJobRequest updateRequest = new JobMcpAdapter.UpdateJobRequest(
                JOB_ID,
                "manual paste",
                "Senior Java Developer",
                "Example Corp",
                "Montreal",
                "Build backend services",
                List.of("Java", "Spring Boot"),
                "5+ years",
                "Full-time",
                "Senior",
                NOW
        );

        assertEquals(new JobService.UpdateJobRequest(
                JOB_ID,
                "manual paste",
                "Senior Java Developer",
                "Example Corp",
                "Montreal",
                "Build backend services",
                List.of("Java", "Spring Boot"),
                "5+ years",
                "Full-time",
                "Senior",
                NOW
        ), updateRequest.toServiceRequest());
    }

    @Test
    void analysisToolsDelegateToAnalysisService() {
        JobMcpAdapter.AnalyzeJobLinkRequest analyzeRequest = new JobMcpAdapter.AnalyzeJobLinkRequest("https://example.test/jobs/1");
        JobAnalysisService.AnalyzeJobLinkRequest serviceAnalyzeRequest = analyzeRequest.toServiceRequest();
        AnalyzeJobLinkResult analyzeResult = new AnalyzeJobLinkResult(
                UUID.fromString("cccccccc-1111-1111-1111-cccccccccccc"),
                "analysis_ready",
                "https://example.test/jobs/1",
                "FETCHED",
                "SUCCEEDED",
                "VALID",
                List.of(),
                Map.of("title", "Java Developer")
        );
        JobMcpAdapter.AddJobFromAnalysisRequest addRequest = new JobMcpAdapter.AddJobFromAnalysisRequest(analyzeResult.analysisRunId());
        JobAnalysisService.AddJobFromAnalysisRequest serviceAddRequest = addRequest.toServiceRequest();
        AddJobFromAnalysisResult addResult = new AddJobFromAnalysisResult(
                analyzeResult.analysisRunId(),
                "created_job",
                new AddJobResult("created_job", sampleAggregate()),
                List.of()
        );
        when(jobAnalysisService.analyzeJobLink(serviceAnalyzeRequest)).thenReturn(analyzeResult);
        when(jobAnalysisService.addJobFromAnalysis(serviceAddRequest)).thenReturn(addResult);

        assertEquals(analyzeResult, adapter.analyzeJobLink(analyzeRequest).structuredContent());
        assertEquals(addResult, adapter.addJobFromAnalysis(addRequest).structuredContent());
        verify(jobAnalysisService).analyzeJobLink(serviceAnalyzeRequest);
        verify(jobAnalysisService).addJobFromAnalysis(serviceAddRequest);
    }

    @Test
    void writeToolsReturnSanitizedValidationErrors() {
        JobMcpAdapter.AddJobFromTextRequest request = new JobMcpAdapter.AddJobFromTextRequest(" ", null, null, null, null, null, null, null, null, null, null);
        when(jobService.addJobFromText(request.toServiceRequest())).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid job request",
                Map.of("field", "text", "reason", "must not be blank"),
                null
        ));

        ApplicationErrorResponse response = assertErrorResponse(adapter.addJobFromText(request));

        assertEquals("validation_error", response.code());
        assertEquals("Invalid job request", response.message());
        assertEquals(Map.of("field", "text", "reason", "must not be blank"), response.details());
    }

    @Test
    void requestRecordFieldAnnotationsMarkOnlyActualRequiredFieldsAsRequired() {
        assertFieldRequired(JobMcpAdapter.AddJobFromTextRequest.class, "text", true);
        assertFieldRequired(JobMcpAdapter.AddJobFromTextRequest.class, "title", false);
        assertFieldRequired(JobMcpAdapter.AddJobFromTextRequest.class, "postedAt", false);
        assertFieldRequired(JobMcpAdapter.AddJobFromLinkRequest.class, "url", true);
        assertFieldRequired(JobMcpAdapter.AddJobFromLinkRequest.class, "skills", false);
        assertFieldRequired(JobMcpAdapter.JobSearchRequest.class, "query", true);
        assertFieldRequired(JobMcpAdapter.JobSearchRequest.class, "limit", false);
        assertFieldRequired(JobMcpAdapter.UpdateJobRequest.class, "jobId", true);
        assertFieldRequired(JobMcpAdapter.UpdateJobRequest.class, "title", false);
        assertFieldRequired(JobMcpAdapter.UpdateJobRequest.class, "skills", false);
        assertFieldRequired(JobMcpAdapter.AnalyzeJobLinkRequest.class, "url", true);
        assertFieldRequired(JobMcpAdapter.AddJobFromAnalysisRequest.class, "analysisRunId", true);
    }

    @Test
    void unexpectedErrorsDoNotExposeExceptionMessages() {
        when(jobService.listJobs(null, null)).thenThrow(new RuntimeException("sensitive backend detail"));

        ApplicationErrorResponse response = assertErrorResponse(adapter.listJobs(null));

        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    @Test
    void nullRequestObjectsReturnSanitizedErrors() {
        assertSafeError(adapter.searchJobs(null));
        assertSafeError(adapter.addJobFromText(null));
        assertSafeError(adapter.addJobFromLink(null));
        assertSafeError(adapter.updateJob(null));
        assertSafeError(adapter.analyzeJobLink(null));
        assertSafeError(adapter.addJobFromAnalysis(null));
    }

    private static void assertSafeError(CallToolResult result) {
        ApplicationErrorResponse response = assertErrorResponse(result);
        assertTrue(Set.of("validation_error", "internal_error").contains(response.code()));
        assertFalse(response.message().contains("Exception"));
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
    }

    private static void assertFieldRequired(Class<? extends Record> recordType, String fieldName, boolean required) {
        RecordComponent component = Arrays.stream(recordType.getRecordComponents())
                .filter(recordComponent -> recordComponent.getName().equals(fieldName))
                .findFirst()
                .orElseThrow();
        McpToolParam annotation = component.getAccessor().getAnnotation(McpToolParam.class);
        if (annotation == null) {
            try {
                annotation = recordType.getDeclaredField(fieldName).getAnnotation(McpToolParam.class);
            } catch (NoSuchFieldException exception) {
                throw new AssertionError(exception);
            }
        }
        assertEquals(required, annotation.required(), fieldName);
    }

    private static JobAggregate sampleAggregate() {
        return new JobAggregate(samplePosting(), List.of(), null, sampleTextIngestion());
    }

    private static JobPosting samplePosting() {
        return new JobPosting(
                JOB_ID,
                "text",
                "manual",
                "Java Developer",
                "Example Corp",
                "Remote",
                "Build backend services",
                "3+ years",
                "Full-time",
                "Mid",
                null,
                "fingerprint",
                NOW,
                NOW
        );
    }

    private static JobTextIngestion sampleTextIngestion() {
        return new JobTextIngestion(
                UUID.fromString("dddddddd-1111-1111-1111-dddddddddddd"),
                JOB_ID,
                "manual",
                "sample-hash",
                NOW
        );
    }

    private static JobSkill sampleSkill() {
        return new JobSkill(
                UUID.fromString("dddddddd-1111-1111-1111-dddddddddddd"),
                JOB_ID,
                "Java",
                "java",
                true,
                0,
                NOW
        );
    }
}

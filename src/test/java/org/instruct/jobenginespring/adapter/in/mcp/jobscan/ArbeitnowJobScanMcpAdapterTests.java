package org.instruct.jobenginespring.adapter.in.mcp.jobscan;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobService;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobScanService;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobImportService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArbeitnowJobScanMcpAdapterTests {

    private final ArbeitnowJobScanService service = mock(ArbeitnowJobScanService.class);
    private final ArbeitnowJobScanMcpAdapter adapter = new ArbeitnowJobScanMcpAdapter(service);

    @Test
    void exposesStableToolNameAndObjectRequestSchema() throws NoSuchMethodException {
        Method scan = ArbeitnowJobScanMcpAdapter.class.getDeclaredMethod("scan", ArbeitnowJobScanMcpAdapter.Request.class);

        assertEquals("scan_arbeitnow_jobs", scan.getAnnotation(McpTool.class).name());
        assertEquals(1, scan.getParameterAnnotations()[0].length);
        assertInstanceOf(McpToolParam.class, scan.getParameterAnnotations()[0][0]);
        assertEquals(9, ArbeitnowJobScanMcpAdapter.Request.class.getRecordComponents().length);
    }

    @Test
    void exposesImportToolWithRequiredObjectCandidateToken() throws NoSuchMethodException {
        Method imported = ArbeitnowJobScanMcpAdapter.class.getDeclaredMethod("importJob", ArbeitnowJobScanMcpAdapter.ImportRequest.class);

        assertEquals("import_arbeitnow_job", imported.getAnnotation(McpTool.class).name());
        assertEquals(1, ArbeitnowJobScanMcpAdapter.ImportRequest.class.getRecordComponents().length);
        assertTrue(((McpToolParam) imported.getParameterAnnotations()[0][0]).required());
    }

    @Test
    void returnsObjectStructuredSuccessAndMapsNullRequestToSafeDefaults() {
        ArbeitnowJobScanService.ScanResult expected = new ArbeitnowJobScanService.ScanResult(
                "arbeitnow", "https://www.arbeitnow.com/api/job-board-api", List.of(), 1, 0, null, "mutable"
        );
        when(service.scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, null, null, null))).thenReturn(expected);

        CallToolResult result = adapter.scan(null);

        assertFalse(result.isError());
        assertEquals(expected, result.structuredContent());
        verify(service).scan(new ArbeitnowJobScanService.ScanRequest(null, null, null, null, null, null, null, null, null));
    }

    @Test
    void returnsSanitizedValidationAndUnexpectedErrorsWithoutRawFailureDetails() {
        ArbeitnowJobScanMcpAdapter.Request request = new ArbeitnowJobScanMcpAdapter.Request("java", null, null, null, null, null, 1, 1, null);
        when(service.scan(new ArbeitnowJobScanService.ScanRequest("java", null, null, null, null, null, 1, 1, null)))
                .thenThrow(new IllegalArgumentException("Invalid Arbeitnow scan request"));

        ApplicationErrorResponse validation = assertError(adapter.scan(request));
        assertEquals("validation_error", validation.code());
        assertEquals("Invalid Arbeitnow scan request", validation.message());

        doThrow(new RuntimeException("raw upstream body <html>secret</html>"))
                .when(service).scan(new ArbeitnowJobScanService.ScanRequest("java", null, null, null, null, null, 1, 1, null));
        ApplicationErrorResponse unexpected = assertError(adapter.scan(request));
        assertEquals("internal_error", unexpected.code());
        assertEquals("Unexpected application error", unexpected.message());
        assertFalse(unexpected.message().contains("html"));
    }

    @Test
    void preservesKnownSanitizedUpstreamFailuresAndMapsUnknownFailuresToInternalError() {
        ArbeitnowJobScanMcpAdapter.Request request = new ArbeitnowJobScanMcpAdapter.Request("java", null, null, null, null, null, 1, 1, null);
        when(service.scan(new ArbeitnowJobScanService.ScanRequest("java", null, null, null, null, null, 1, 1, null)))
                .thenThrow(new ApplicationException(ApplicationErrorCode.UPSTREAM_RATE_LIMITED, "Arbeitnow job board is temporarily unavailable", java.util.Map.of("provider", "arbeitnow", "failureCategory", "rate_limited", "retryable", "true", "retryAfterSeconds", "30"), null));

        ApplicationErrorResponse upstream = assertError(adapter.scan(request));

        assertEquals("upstream_rate_limited", upstream.code());
        assertEquals("Arbeitnow job board is temporarily unavailable", upstream.message());
        assertEquals("30", upstream.details().get("retryAfterSeconds"));
        assertEquals("rate_limited", upstream.details().get("failureCategory"));
    }

    @Test
    void importsSignedCandidatesAndSanitizesMissingAndUnexpectedImportFailures() {
        ArbeitnowJobImportService imports = mock(ArbeitnowJobImportService.class);
        ArbeitnowJobScanMcpAdapter importingAdapter = new ArbeitnowJobScanMcpAdapter(service, imports);
        when(imports.importCandidate("signed-token")).thenReturn(new JobService.AddJobResult("created_job", null));

        CallToolResult success = importingAdapter.importJob(new ArbeitnowJobScanMcpAdapter.ImportRequest("signed-token"));
        assertFalse(success.isError());
        verify(imports).importCandidate("signed-token");

        assertEquals("validation_error", assertError(importingAdapter.importJob(null)).code());
        assertEquals("validation_error", assertError(importingAdapter.importJob(new ArbeitnowJobScanMcpAdapter.ImportRequest(null))).code());
        assertEquals("validation_error", assertError(importingAdapter.importJob(new ArbeitnowJobScanMcpAdapter.ImportRequest(" "))).code());
        doThrow(new RuntimeException("raw token secret")).when(imports).importCandidate("bad-token");
        ApplicationErrorResponse unexpected = assertError(importingAdapter.importJob(new ArbeitnowJobScanMcpAdapter.ImportRequest("bad-token")));
        assertEquals("internal_error", unexpected.code());
        assertFalse(unexpected.message().contains("secret"));
    }

    private static ApplicationErrorResponse assertError(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
    }
}

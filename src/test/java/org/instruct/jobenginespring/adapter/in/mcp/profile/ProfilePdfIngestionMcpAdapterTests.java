package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.adapter.in.mcp.profile.ProfilePdfIngestionMcpAdapter.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.ProfilePdfIngestionResult;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
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

class ProfilePdfIngestionMcpAdapterTests {

    private static final UUID DOCUMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROFILE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EXTRACTION_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SOURCE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private final ProfilePdfIngestionService service = mock(ProfilePdfIngestionService.class);
    private final ProfilePdfIngestionMcpAdapter adapter = new ProfilePdfIngestionMcpAdapter(service);

    @Test
    void exposesStableProfilePdfIngestionToolNames() {
        Set<String> toolNames = Arrays.stream(ProfilePdfIngestionMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("ingest_profile_from_stored_pdf", "get_profile_pdf_source"), toolNames);
    }

    @Test
    void toolsDescribeRequestParameters() throws NoSuchMethodException, NoSuchFieldException {
        Method ingest = ProfilePdfIngestionMcpAdapter.class.getDeclaredMethod(
                "ingestProfileFromStoredPdf",
                IngestProfileFromStoredPdfRequest.class
        );
        Method getSource = ProfilePdfIngestionMcpAdapter.class.getDeclaredMethod("getProfilePdfSource", UUID.class);

        assertEquals("ingest_profile_from_stored_pdf", ingest.getAnnotation(McpTool.class).name());
        assertEquals("get_profile_pdf_source", getSource.getAnnotation(McpTool.class).name());
        assertEquals(1, ingest.getParameterAnnotations()[0].length);
        assertEquals(1, getSource.getParameterAnnotations()[0].length);
        McpToolParam expectedRevision = IngestProfileFromStoredPdfRequest.class
                .getDeclaredField("expectedRevision")
                .getAnnotation(McpToolParam.class);
        assertFalse(expectedRevision.required());
        assertTrue(expectedRevision.description().contains("revision"));
    }

    @Test
    void ingestProfileFromStoredPdfDelegatesToService() {
        IngestProfileFromStoredPdfRequest request = new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, null, null, 10_000, null);
        ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest serviceRequest = request.toServiceRequest();
        ProfilePdfIngestionResult expected = new ProfilePdfIngestionResult(
                IngestionStatus.CREATED_PROFILE,
                PROFILE_ID,
                DOCUMENT_ID,
                EXTRACTION_ID,
                SOURCE_ID,
                "resume.pdf",
                1,
                42,
                false,
                true,
                false,
                null,
                java.util.List.of(),
                null
        );
        when(service.ingestProfileFromStoredPdf(serviceRequest)).thenReturn(expected);

        CallToolResult result = adapter.ingestProfileFromStoredPdf(request);

        assertFalse(result.isError());
        assertEquals(expected, result.structuredContent());
        verify(service).ingestProfileFromStoredPdf(serviceRequest);
    }

    @Test
    void getProfilePdfSourceDelegatesToService() {
        ProfilePdfSource expected = new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", Instant.parse("2026-07-03T19:00:00Z"));
        when(service.getProfilePdfSource(PROFILE_ID)).thenReturn(expected);

        CallToolResult result = adapter.getProfilePdfSource(PROFILE_ID);

        assertFalse(result.isError());
        assertEquals(expected, result.structuredContent());
        verify(service).getProfilePdfSource(PROFILE_ID);
    }

    @Test
    void returnsSanitizedApplicationErrors() {
        IngestProfileFromStoredPdfRequest request = new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, null, null, null, null);
        when(service.ingestProfileFromStoredPdf(request.toServiceRequest())).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile extraction input",
                Map.of("field", "email", "reason", "could not be extracted from PDF text"),
                null
        ));

        CallToolResult result = adapter.ingestProfileFromStoredPdf(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid profile extraction input", response.message());
    }

    @Test
    void passesNullRequestToSanitizedErrorBoundary() {
        when(service.ingestProfileFromStoredPdf(null)).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile ingestion request",
                Map.of("field", "request", "reason", "must not be null"),
                null
        ));

        CallToolResult result = adapter.ingestProfileFromStoredPdf(null);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid profile ingestion request", response.message());
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
    }
}

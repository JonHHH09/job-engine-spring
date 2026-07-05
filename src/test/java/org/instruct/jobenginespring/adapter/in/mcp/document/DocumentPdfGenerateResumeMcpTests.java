package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeRequest;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeResult;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPdfGenerateResumeMcpTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LINK_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOCUMENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-07-05T16:00:00Z");

    private final GeneratePdfResumeService generatePdfResumeService = mock(GeneratePdfResumeService.class);
    private final DocumentPdfGenerateResumeMcp adapter = new DocumentPdfGenerateResumeMcp(generatePdfResumeService);

    @Test
    void exposesStableGeneratePdfResumeToolName() throws NoSuchMethodException {
        Method method = DocumentPdfGenerateResumeMcp.class.getDeclaredMethod(
                "generatePdfResume",
                GeneratePdfResumeRequest.class
        );

        assertEquals("generate_pdf_resume", method.getAnnotation(McpTool.class).name());
        assertEquals(1, method.getParameterAnnotations()[0].length);
    }

    @Test
    void generatePdfResumeDelegatesToService() {
        GeneratePdfResumeRequest request = new GeneratePdfResumeRequest(PROFILE_ID);
        GeneratePdfResumeResult serviceResult = sampleResult();
        when(generatePdfResumeService.generatePdfResume(request)).thenReturn(serviceResult);

        CallToolResult result = adapter.generatePdfResume(request);

        assertFalse(result.isError());
        assertEquals(serviceResult, result.structuredContent());
        verify(generatePdfResumeService).generatePdfResume(request);
    }

    @Test
    void returnsSanitizedApplicationErrors() {
        GeneratePdfResumeRequest request = new GeneratePdfResumeRequest(PROFILE_ID);
        ApplicationException validationFailure = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid resume PDF generation request",
                Map.of("field", "profileId", "reason", "must not be null"),
                null
        );
        when(generatePdfResumeService.generatePdfResume(request)).thenThrow(validationFailure);

        CallToolResult result = adapter.generatePdfResume(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid resume PDF generation request", response.message());
        assertEquals(Map.of("field", "profileId", "reason", "must not be null"), response.details());
    }

    @Test
    void unexpectedErrorsDoNotExposeExceptionMessages() {
        GeneratePdfResumeRequest request = new GeneratePdfResumeRequest(PROFILE_ID);
        when(generatePdfResumeService.generatePdfResume(request)).thenThrow(new RuntimeException("private path detail"));

        CallToolResult result = adapter.generatePdfResume(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
    }

    private static GeneratePdfResumeResult sampleResult() {
        StoredDocumentMetadata metadata = new StoredDocumentMetadata(
                DOCUMENT_ID,
                "master-resume.pdf",
                "application/pdf",
                512,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                NOW,
                NOW
        );
        GeneratedPdfFileResult generated = new GeneratedPdfFileResult(
                "master-resume.pdf",
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                512,
                1,
                NOW.toString()
        );
        return new GeneratePdfResumeResult(
                PROFILE_ID,
                LINK_ID,
                DOCUMENT_ID,
                generated.path(),
                GeneratePdfResumeService.MASTER_RESUME_TYPE,
                metadata,
                generated,
                false,
                NOW.toString()
        );
    }
}

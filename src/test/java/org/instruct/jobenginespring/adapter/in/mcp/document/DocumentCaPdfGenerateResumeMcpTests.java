package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.document.GenerateCaPdfResumeService;
import org.instruct.jobenginespring.application.document.GenerateCaPdfResumeService.GenerateCaPdfResumeRequest;
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

class DocumentCaPdfGenerateResumeMcpTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LINK_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOCUMENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-07-05T16:00:00Z");

    private final GenerateCaPdfResumeService generateCaPdfResumeService = mock(GenerateCaPdfResumeService.class);
    private final DocumentCaPdfGenerateResumeMcp adapter = new DocumentCaPdfGenerateResumeMcp(generateCaPdfResumeService);

    @Test
    void exposesStableGenerateCanadianPdfResumeToolName() throws NoSuchMethodException {
        Method method = DocumentCaPdfGenerateResumeMcp.class.getDeclaredMethod(
                "generateCanadianPdfResume",
                GenerateCaPdfResumeRequest.class
        );

        assertEquals("generate_canadian_pdf_resume", method.getAnnotation(McpTool.class).name());
        assertEquals(1, method.getParameterAnnotations()[0].length);
    }

    @Test
    void generateCanadianPdfResumeDelegatesToService() {
        GenerateCaPdfResumeRequest request = new GenerateCaPdfResumeRequest(PROFILE_ID);
        GeneratePdfResumeResult serviceResult = sampleResult();
        when(generateCaPdfResumeService.generateCanadianPdfResume(request)).thenReturn(serviceResult);

        CallToolResult result = adapter.generateCanadianPdfResume(request);

        assertFalse(result.isError());
        assertEquals(serviceResult, result.structuredContent());
        verify(generateCaPdfResumeService).generateCanadianPdfResume(request);
    }

    @Test
    void returnsSanitizedApplicationErrors() {
        GenerateCaPdfResumeRequest request = new GenerateCaPdfResumeRequest(PROFILE_ID);
        ApplicationException validationFailure = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid resume PDF generation request",
                Map.of("field", "profileId", "reason", "must not be null"),
                null
        );
        when(generateCaPdfResumeService.generateCanadianPdfResume(request)).thenThrow(validationFailure);

        CallToolResult result = adapter.generateCanadianPdfResume(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid resume PDF generation request", response.message());
        assertEquals(Map.of("field", "profileId", "reason", "must not be null"), response.details());
    }

    @Test
    void unexpectedErrorsDoNotExposeExceptionMessages() {
        GenerateCaPdfResumeRequest request = new GenerateCaPdfResumeRequest(PROFILE_ID);
        when(generateCaPdfResumeService.generateCanadianPdfResume(request)).thenThrow(new RuntimeException("private path detail"));

        CallToolResult result = adapter.generateCanadianPdfResume(request);

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
                "canadian-resume.pdf",
                "application/pdf",
                512,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                NOW,
                NOW
        );
        GeneratedPdfFileResult generated = new GeneratedPdfFileResult(
                "canadian-resume.pdf",
                "tmp/generated-pdfs/canadian-resume/canadian-resume.pdf",
                512,
                1,
                NOW.toString()
        );
        return new GeneratePdfResumeResult(
                PROFILE_ID,
                LINK_ID,
                DOCUMENT_ID,
                generated.path(),
                GenerateCaPdfResumeService.CANADIAN_RESUME_TYPE,
                metadata,
                generated,
                false,
                NOW.toString()
        );
    }
}

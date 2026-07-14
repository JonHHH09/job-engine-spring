package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.document.GenerateCanadianFrenchPdfResumeService;
import org.instruct.jobenginespring.application.document.GenerateCanadianFrenchPdfResumeService.GenerateCanadianFrenchPdfResumeRequest;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentCanadianFrenchPdfGenerateResumeMcpTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-07-14T01:00:00Z");

    private final GenerateCanadianFrenchPdfResumeService service = mock(GenerateCanadianFrenchPdfResumeService.class);
    private final DocumentCanadianFrenchPdfGenerateResumeMcp adapter = new DocumentCanadianFrenchPdfGenerateResumeMcp(service);

    @Test
    void exposesStableFrenchCanadianResumeToolName() throws NoSuchMethodException {
        Method method = DocumentCanadianFrenchPdfGenerateResumeMcp.class.getDeclaredMethod(
                "generateCanadianFrenchPdfResume",
                GenerateCanadianFrenchPdfResumeRequest.class
        );

        assertEquals("generate_canadian_french_pdf_resume", method.getAnnotation(McpTool.class).name());
        assertEquals(1, method.getParameterAnnotations()[0].length);
    }

    @Test
    void delegatesToFrenchCanadianResumeService() {
        GenerateCanadianFrenchPdfResumeRequest request = new GenerateCanadianFrenchPdfResumeRequest(PROFILE_ID);
        GeneratePdfResumeResult serviceResult = sampleResult();
        when(service.generateCanadianFrenchPdfResume(request)).thenReturn(serviceResult);

        CallToolResult result = adapter.generateCanadianFrenchPdfResume(request);

        assertFalse(result.isError());
        assertEquals(serviceResult, result.structuredContent());
        verify(service).generateCanadianFrenchPdfResume(request);
    }

    @Test
    void mapsApplicationAndUnexpectedErrorsWithoutLeakingDetails() {
        GenerateCanadianFrenchPdfResumeRequest request = new GenerateCanadianFrenchPdfResumeRequest(PROFILE_ID);
        when(service.generateCanadianFrenchPdfResume(request)).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid resume PDF generation request",
                Map.of("field", "profileId", "reason", "must not be null"),
                null
        ));

        CallToolResult applicationFailure = adapter.generateCanadianFrenchPdfResume(request);

        assertTrue(applicationFailure.isError());
        ApplicationErrorResponse applicationResponse = assertInstanceOf(
                ApplicationErrorResponse.class,
                applicationFailure.structuredContent()
        );
        assertEquals("validation_error", applicationResponse.code());
        assertEquals(Map.of("field", "profileId", "reason", "must not be null"), applicationResponse.details());

        doThrow(new RuntimeException("private path detail"))
                .when(service).generateCanadianFrenchPdfResume(request);
        CallToolResult unexpectedFailure = adapter.generateCanadianFrenchPdfResume(request);
        ApplicationErrorResponse unexpectedResponse = assertInstanceOf(
                ApplicationErrorResponse.class,
                unexpectedFailure.structuredContent()
        );
        assertTrue(unexpectedFailure.isError());
        assertEquals("internal_error", unexpectedResponse.code());
        assertEquals("Unexpected application error", unexpectedResponse.message());
        assertEquals(Map.of(), unexpectedResponse.details());
    }

    private static GeneratePdfResumeResult sampleResult() {
        UUID documentId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        StoredDocumentMetadata metadata = new StoredDocumentMetadata(
                documentId,
                "canadian-resume-fr.pdf",
                "application/pdf",
                512,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                NOW,
                NOW
        );
        GeneratedPdfFileResult generated = new GeneratedPdfFileResult(
                "canadian-resume-fr.pdf",
                "tmp/generated-pdfs/canadian-resume-fr/canadian-resume-fr.pdf",
                512,
                1,
                NOW.toString()
        );
        return new GeneratePdfResumeResult(
                PROFILE_ID,
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                documentId,
                generated.path(),
                GenerateCanadianFrenchPdfResumeService.CANADIAN_FRENCH_RESUME_TYPE,
                metadata,
                generated,
                false,
                NOW.toString()
        );
    }
}

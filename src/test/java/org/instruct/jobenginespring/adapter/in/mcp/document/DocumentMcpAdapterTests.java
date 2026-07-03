package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoreDocumentFileRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionRequest;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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

class DocumentMcpAdapterTests {

    private static final UUID DOCUMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final StoredDocumentMetadata DOCUMENT_METADATA = new StoredDocumentMetadata(
            DOCUMENT_ID,
            "sample.pdf",
            "application/pdf",
            128,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            Instant.parse("2026-07-03T17:45:00Z"),
            Instant.parse("2026-07-03T17:45:00Z")
    );

    private final PdfTextExtractionService pdfTextExtractionService = mock(PdfTextExtractionService.class);
    private final DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
    private final DocumentMcpAdapter adapter = new DocumentMcpAdapter(pdfTextExtractionService, documentStorageService);

    @Test
    void exposesStableDocumentToolNames() {
        Set<String> toolNames = Arrays.stream(DocumentMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "extract_pdf_text",
                "store_document_file",
                "get_document_metadata",
                "extract_stored_pdf_text"
        ), toolNames);
    }

    @Test
    void documentToolsDescribeRequestParameters() throws NoSuchMethodException {
        Method extractPdfText = DocumentMcpAdapter.class.getDeclaredMethod(
                "extractPdfText",
                PdfTextExtractionRequest.class
        );
        Method storeDocumentFile = DocumentMcpAdapter.class.getDeclaredMethod(
                "storeDocumentFile",
                StoreDocumentFileRequest.class
        );
        Method getDocumentMetadata = DocumentMcpAdapter.class.getDeclaredMethod(
                "getDocumentMetadata",
                UUID.class
        );
        Method extractStoredPdfText = DocumentMcpAdapter.class.getDeclaredMethod(
                "extractStoredPdfText",
                ExtractStoredPdfTextRequest.class
        );

        assertEquals("extract_pdf_text", extractPdfText.getAnnotation(McpTool.class).name());
        assertEquals("store_document_file", storeDocumentFile.getAnnotation(McpTool.class).name());
        assertEquals("get_document_metadata", getDocumentMetadata.getAnnotation(McpTool.class).name());
        assertEquals("extract_stored_pdf_text", extractStoredPdfText.getAnnotation(McpTool.class).name());
        assertEquals(1, extractPdfText.getParameterAnnotations()[0].length);
        assertEquals(1, storeDocumentFile.getParameterAnnotations()[0].length);
        assertEquals(1, getDocumentMetadata.getParameterAnnotations()[0].length);
        assertEquals(1, extractStoredPdfText.getParameterAnnotations()[0].length);
    }

    @Test
    void extractPdfTextDelegatesToService() {
        PdfTextExtractionRequest request = new PdfTextExtractionRequest("sample.pdf", 1_000, true);
        PdfTextExtractionResult extractionResult = sampleExtractionResult();
        when(pdfTextExtractionService.extractText(request)).thenReturn(extractionResult);

        CallToolResult result = adapter.extractPdfText(request);

        assertFalse(result.isError());
        assertEquals(extractionResult, result.structuredContent());
        verify(pdfTextExtractionService).extractText(request);
    }

    @Test
    void storeDocumentFileDelegatesToService() {
        StoreDocumentFileRequest request = new StoreDocumentFileRequest("sample.pdf", null);
        when(documentStorageService.storeDocumentFile(request)).thenReturn(DOCUMENT_METADATA);

        CallToolResult result = adapter.storeDocumentFile(request);

        assertFalse(result.isError());
        assertEquals(DOCUMENT_METADATA, result.structuredContent());
        verify(documentStorageService).storeDocumentFile(request);
    }

    @Test
    void getDocumentMetadataDelegatesToService() {
        when(documentStorageService.getDocumentMetadata(DOCUMENT_ID)).thenReturn(DOCUMENT_METADATA);

        CallToolResult result = adapter.getDocumentMetadata(DOCUMENT_ID);

        assertFalse(result.isError());
        assertEquals(DOCUMENT_METADATA, result.structuredContent());
        verify(documentStorageService).getDocumentMetadata(DOCUMENT_ID);
    }

    @Test
    void extractStoredPdfTextDelegatesToService() {
        ExtractStoredPdfTextRequest request = new ExtractStoredPdfTextRequest(DOCUMENT_ID, 1_000, false, true);
        StoredPdfTextExtractionResult extractionResult = new StoredPdfTextExtractionResult(
                DOCUMENT_METADATA,
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                sampleExtractionResult()
        );
        when(documentStorageService.extractStoredPdfText(request)).thenReturn(extractionResult);

        CallToolResult result = adapter.extractStoredPdfText(request);

        assertFalse(result.isError());
        assertEquals(extractionResult, result.structuredContent());
        verify(documentStorageService).extractStoredPdfText(request);
    }

    @Test
    void returnsSanitizedValidationErrors() {
        PdfTextExtractionRequest request = new PdfTextExtractionRequest("missing.pdf", null, null);
        ApplicationException validationFailure = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid PDF text extraction request",
                Map.of("field", "path", "reason", "file was not found"),
                null
        );
        when(pdfTextExtractionService.extractText(request)).thenThrow(validationFailure);

        CallToolResult result = adapter.extractPdfText(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid PDF text extraction request", response.message());
        assertEquals(Map.of("field", "path", "reason", "file was not found"), response.details());
    }

    @Test
    void toolErrorsDoNotExposeUnexpectedExceptionMessages() {
        PdfTextExtractionRequest request = new PdfTextExtractionRequest("sample.pdf", null, null);
        when(pdfTextExtractionService.extractText(request)).thenThrow(new RuntimeException("sensitive local path detail"));

        CallToolResult result = adapter.extractPdfText(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    private static PdfTextExtractionResult sampleExtractionResult() {
        return new PdfTextExtractionResult(
                "sample.pdf",
                1,
                12,
                false,
                "sample text",
                List.of(new PdfTextExtractionService.ExtractedPdfPage(1, "sample text"))
        );
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
    }
}

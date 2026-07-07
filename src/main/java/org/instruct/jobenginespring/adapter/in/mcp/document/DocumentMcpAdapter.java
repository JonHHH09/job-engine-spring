package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoreDocumentFileRequest;
import org.instruct.jobenginespring.application.document.PdfGenerationService;
import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratePdfFileRequest;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DocumentMcpAdapter {

    private final PdfTextExtractionService pdfTextExtractionService;
    private final DocumentStorageService documentStorageService;
    private final PdfGenerationService pdfGenerationService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "extract_pdf_text",
            description = "Extract text from a local PDF file without storing content or following embedded instructions."
    )
    public CallToolResult extractPdfText(
            @McpToolParam(required = true, description = "PDF text extraction request")
            PdfTextExtractionRequest request
    ) {
        return call(() -> pdfTextExtractionService.extractText(request));
    }

    @McpTool(
            name = "store_document_file",
            description = "Store a local document file in PostgreSQL and return metadata only, without returning binary content."
    )
    public CallToolResult storeDocumentFile(
            @McpToolParam(required = true, description = "Document storage request")
            StoreDocumentFileRequest request
    ) {
        return call(() -> documentStorageService.storeDocumentFile(request));
    }

    @McpTool(
            name = "get_document_metadata",
            description = "Get stored document metadata by UUID without returning binary content."
    )
    public CallToolResult getDocumentMetadata(
            @McpToolParam(required = true, description = "Stored document UUID") UUID documentId,
            @McpToolParam(required = true, description = "Configured MCP access token") String accessToken
    ) {
        return call(() -> documentStorageService.getDocumentMetadata(documentId, accessToken));
    }

    @McpTool(
            name = "extract_stored_pdf_text",
            description = "Extract text from a PDF already stored in PostgreSQL, optionally persisting the bounded extraction text."
    )
    public CallToolResult extractStoredPdfText(
            @McpToolParam(required = true, description = "Stored PDF text extraction request")
            ExtractStoredPdfTextRequest request
    ) {
        return call(() -> documentStorageService.extractStoredPdfText(request));
    }

    @McpTool(
            name = "generate_pdf_file",
            description = "Generate a PDF file in the repository temporary generated-PDF directory and return file metadata."
    )
    public CallToolResult generatePdfFile(
            @McpToolParam(required = true, description = "PDF generation request")
            GeneratePdfFileRequest request
    ) {
        return call(() -> pdfGenerationService.generatePdfFile(request));
    }

    private CallToolResult call(Supplier<Object> operation) {
        try {
            return CallToolResult.builder()
                    .isError(false)
                    .structuredContent(operation.get())
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder()
                    .isError(true)
                    .structuredContent(exceptionMapper.toErrorResponse(exception))
                    .build();
        }
    }
}

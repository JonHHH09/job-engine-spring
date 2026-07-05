package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.GenerateCaPdfResumeService;
import org.instruct.jobenginespring.application.document.GenerateCaPdfResumeService.GenerateCaPdfResumeRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DocumentCaPdfGenerateResumeMcp {

    private final GenerateCaPdfResumeService generateCaPdfResumeService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "generate_canadian_pdf_resume",
            description = "Generate a Canadian-format resume PDF from profile schema data, store it as a document, and link it uniquely to the profile as a resume variant."
    )
    public CallToolResult generateCanadianPdfResume(
            @McpToolParam(required = true, description = "Canadian resume PDF generation request")
            GenerateCaPdfResumeRequest request
    ) {
        // Keep the MCP boundary thin: protocol binding and sanitized error mapping live here,
        // while Canadian resume rendering, PDF generation, storage, and profile-linking stay in application services.
        return call(() -> generateCaPdfResumeService.generateCanadianPdfResume(request));
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

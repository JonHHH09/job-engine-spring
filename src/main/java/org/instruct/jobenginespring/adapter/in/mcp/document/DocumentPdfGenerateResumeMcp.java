package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DocumentPdfGenerateResumeMcp {

    private final GeneratePdfResumeService generatePdfResumeService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "generate_pdf_resume",
            description = "Generate a master resume PDF from profile schema data, store it as a document, and link it one-to-one to the profile."
    )
    public CallToolResult generatePdfResume(
            @McpToolParam(required = true, description = "Master resume PDF generation request")
            GeneratePdfResumeRequest request
    ) {
        return call(() -> generatePdfResumeService.generatePdfResume(request));
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

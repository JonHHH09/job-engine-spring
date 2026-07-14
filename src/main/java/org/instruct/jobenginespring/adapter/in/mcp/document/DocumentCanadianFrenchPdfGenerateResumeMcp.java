package org.instruct.jobenginespring.adapter.in.mcp.document;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.GenerateCanadianFrenchPdfResumeService;
import org.instruct.jobenginespring.application.document.GenerateCanadianFrenchPdfResumeService.GenerateCanadianFrenchPdfResumeRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentCanadianFrenchPdfGenerateResumeMcp {

    private final GenerateCanadianFrenchPdfResumeService generateService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "generate_canadian_french_pdf_resume",
            description = "Generate a French-language Canadian-format resume PDF from profile schema data, store it as a document, and link it independently to the profile."
    )
    public CallToolResult generateCanadianFrenchPdfResume(
            @McpToolParam(required = true, description = "French Canadian resume PDF generation request")
            GenerateCanadianFrenchPdfResumeRequest request
    ) {
        try {
            return CallToolResult.builder()
                    .isError(false)
                    .structuredContent(generateService.generateCanadianFrenchPdfResume(request))
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder()
                    .isError(true)
                    .structuredContent(exceptionMapper.toErrorResponse(exception))
                    .build();
        }
    }
}

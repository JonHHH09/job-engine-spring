package org.instruct.jobenginespring.adapter.in.mcp.coverletter;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.GenerateGermanCoverLetterService;
import org.instruct.jobenginespring.application.document.GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class GermanCoverLetterMcp {

    private final GenerateGermanCoverLetterService generateGermanCoverLetterService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "generate_german_cover_letter",
            description = "Generate one deterministic Germany-format German cover letter for a profile, job, and exact Germany-format tailored resume. The structured subject, salutation, paragraphs, closing, and signature are persisted separately from the PDF; the response returns metadata only."
    )
    public CallToolResult generateGermanCoverLetter(
            @McpToolParam(required = true, description = "German cover-letter generation request")
            GenerateGermanCoverLetterRequest request
    ) {
        return call(() -> generateGermanCoverLetterService.generate(request));
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

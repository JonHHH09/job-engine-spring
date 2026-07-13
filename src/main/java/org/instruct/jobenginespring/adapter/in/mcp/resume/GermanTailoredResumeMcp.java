package org.instruct.jobenginespring.adapter.in.mcp.resume;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.document.GenerateGermanTailoredResumeService;
import org.instruct.jobenginespring.application.document.GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class GermanTailoredResumeMcp {

    private final GenerateGermanTailoredResumeService generateGermanTailoredResumeService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "generate_german_tailored_resume",
            description = "Generate a Germany-format tailored Lebenslauf for one profile and job as bilingual EN+DE structured content and PDFs, linked under one resume parent row."
    )
    public CallToolResult generateGermanTailoredResume(
            @McpToolParam(required = true, description = "German tailored resume generation request")
            GenerateGermanTailoredResumeRequest request
    ) {
        return call(() -> generateGermanTailoredResumeService.generate(request));
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

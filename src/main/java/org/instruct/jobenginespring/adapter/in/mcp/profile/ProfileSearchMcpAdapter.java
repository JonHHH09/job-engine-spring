package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.profile.ProfileSearchService;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchRequest;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class ProfileSearchMcpAdapter {

    private final ProfileSearchService profileSearchService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "search_profiles",
            description = "Search normalized profile identities by query terms across profile, skills, experience, education, projects, links, contacts, and languages without returning raw resume text."
    )
    public CallToolResult searchProfiles(
            @McpToolParam(required = true,
                    description = "Profile search query (maximum 256 characters and 16 searchable terms) and optional result limit")
            ProfileSearchRequest request
    ) {
        return call(() -> profileSearchService.searchProfiles(request));
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

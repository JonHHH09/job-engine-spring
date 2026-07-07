package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class ProfilePdfIngestionMcpAdapter {

    private final ProfilePdfIngestionService profilePdfIngestionService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "ingest_profile_from_stored_pdf",
            description = "Populate the normalized profile schema from a stored PDF extraction and link the profile to that extraction."
    )
    public CallToolResult ingestProfileFromStoredPdf(
            @McpToolParam(required = true, description = "Stored PDF profile ingestion request")
            IngestProfileFromStoredPdfRequest request
    ) {
        return call(() -> profilePdfIngestionService.ingestProfileFromStoredPdf(request));
    }

    @McpTool(
            name = "get_profile_pdf_source",
            description = "Get the one-to-one PDF extraction source link for a profile."
    )
    public CallToolResult getProfilePdfSource(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId
    ) {
        return call(() -> profilePdfIngestionService.getProfilePdfSource(profileId));
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

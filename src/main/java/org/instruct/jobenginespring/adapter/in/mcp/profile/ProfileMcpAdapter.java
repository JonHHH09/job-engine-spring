package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class ProfileMcpAdapter {

    private final ProfileService profileService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @McpTool(
            name = "list_profiles",
            description = "List available profile identities without returning raw resume text or private credentials."
    )
    public CallToolResult listProfiles() {
        return call(() -> new ListProfilesResult(profileService.listProfiles()));
    }

    @McpTool(
            name = "get_profile",
            description = "Get a normalized profile aggregate by profile UUID."
    )
    public CallToolResult getProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId
    ) {
        return call(() -> profileService.getProfile(profileId)
                .orElseThrow(() -> new ProfileService.ProfileNotFoundException(profileId)));
    }

    @McpTool(
            name = "create_profile",
            description = "Create a normalized profile aggregate and return the persisted profile graph."
    )
    public CallToolResult createProfile(
            @McpToolParam(required = true, description = "Profile fields and optional child collections")
            ProfileWriteRequest request
    ) {
        return call(() -> profileService.createProfile(request));
    }

    @McpTool(
            name = "update_profile",
            description = "Replace a normalized profile aggregate by profile UUID and expected revision, then return the persisted profile graph."
    )
    public CallToolResult updateProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId,
            @McpToolParam(required = true, description = "Revision returned by the latest profile read") Long expectedRevision,
            @McpToolParam(required = true, description = "Replacement profile fields and optional child collections")
            ProfileWriteRequest request
    ) {
        return call(() -> profileService.updateProfile(profileId, expectedRevision, request));
    }

    @McpTool(
            name = "delete_profile",
            description = "Delete a profile aggregate by profile UUID. Child profile data is removed by database cascade."
    )
    public CallToolResult deleteProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId
    ) {
        return call(() -> new DeleteProfileResult(profileId, profileService.deleteProfile(profileId)));
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

    public record DeleteProfileResult(UUID profileId, boolean deleted) {
    }

    public record ListProfilesResult(List<UserProfile> profiles) {
    }
}

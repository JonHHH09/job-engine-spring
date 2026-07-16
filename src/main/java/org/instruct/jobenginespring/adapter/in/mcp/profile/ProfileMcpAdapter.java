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
            description = "List a bounded page of profile identities without returning raw resume text or private credentials."
    )
    public CallToolResult listProfiles(
            @McpToolParam(required = false, description = "Optional page size and cursor from the previous response")
            ListRequest request
    ) {
        return call(() -> {
            var safeRequest = request == null ? new ListRequest(null, null) : request;
            var page = profileService.listProfiles(safeRequest.limit(), safeRequest.cursor());
            return new ListProfilesResult(page.items(), page.nextCursor());
        });
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
            name = "update_profile_project",
            description = "Partially update one profile project using the current profile revision; omitted project fields are preserved and supplied technologies replace that project's technologies."
    )
    public CallToolResult updateProfileProject(
            @McpToolParam(required = true, description = "Profile project partial update request")
            ProjectUpdateRequest request
    ) {
        return call(() -> profileService.updateProject(request == null ? null : request.toServiceRequest()));
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

    public record ListRequest(
            @McpToolParam(required = false, description = "Maximum page size, 1-100") Integer limit,
            @McpToolParam(required = false, description = "Opaque cursor returned by the previous page") String cursor
    ) {
    }

    public record ListProfilesResult(List<UserProfile> profiles, String nextCursor) {
        public ListProfilesResult {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    public record ProjectUpdateRequest(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId,
            @McpToolParam(required = true, description = "Existing project UUID") UUID projectId,
            @McpToolParam(required = true, description = "Revision returned by the latest profile read") Long expectedRevision,
            @McpToolParam(required = false, description = "Project name") String name,
            @McpToolParam(required = false, description = "Project URL") String url,
            @McpToolParam(required = false, description = "Project description") String description,
            @McpToolParam(required = false, description = "Project display order") Integer displayOrder,
            @McpToolParam(required = false, description = "Replacement project technologies; omit to preserve existing technologies")
            List<ProfileService.ProjectTechnologyWriteRequest> technologies
    ) {
        ProfileService.ProjectUpdateRequest toServiceRequest() {
            return new ProfileService.ProjectUpdateRequest(
                    profileId, projectId, expectedRevision, name, url, description, displayOrder, technologies
            );
        }
    }
}

package org.instruct.jobenginespring.adapter.in.mcp.profile;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProfileMcpAdapter {

    private final ProfileService profileService;

    @McpTool(
            name = "list_profiles",
            description = "List available profile identities without returning raw resume text or private credentials."
    )
    public List<UserProfile> listProfiles() {
        return profileService.listProfiles();
    }

    @McpTool(
            name = "get_profile",
            description = "Get a normalized profile aggregate by profile UUID."
    )
    public ProfileAggregate getProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId
    ) {
        return profileService.getProfile(profileId)
                .orElseThrow(() -> new ProfileService.ProfileNotFoundException(profileId));
    }

    @McpTool(
            name = "create_profile",
            description = "Create a normalized profile aggregate and return the persisted profile graph."
    )
    public ProfileAggregate createProfile(
            @McpToolParam(required = true, description = "Profile fields and optional child collections")
            ProfileWriteRequest request
    ) {
        return profileService.createProfile(request);
    }

    @McpTool(
            name = "update_profile",
            description = "Replace a normalized profile aggregate by profile UUID and return the persisted profile graph."
    )
    public ProfileAggregate updateProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId,
            @McpToolParam(required = true, description = "Replacement profile fields and optional child collections")
            ProfileWriteRequest request
    ) {
        return profileService.updateProfile(profileId, request);
    }

    @McpTool(
            name = "delete_profile",
            description = "Delete a profile aggregate by profile UUID. Child profile data is removed by database cascade."
    )
    public DeleteProfileResult deleteProfile(
            @McpToolParam(required = true, description = "Profile UUID") UUID profileId
    ) {
        return new DeleteProfileResult(profileId, profileService.deleteProfile(profileId));
    }

    public record DeleteProfileResult(UUID profileId, boolean deleted) {
    }
}

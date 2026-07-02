package org.instruct.jobenginespring.adapter.in.mcp.profile;

import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileMcpAdapterTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-07-02T15:30:00Z");

    private final ProfileService profileService = mock(ProfileService.class);
    private final ProfileMcpAdapter adapter = new ProfileMcpAdapter(profileService);

    @Test
    void exposesStableProfileCrudToolNames() {
        Set<String> toolNames = Arrays.stream(ProfileMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "list_profiles",
                "get_profile",
                "create_profile",
                "update_profile",
                "delete_profile"
        ), toolNames);
    }

    @Test
    void createAndUpdateToolsDescribeRequestParameters() throws NoSuchMethodException {
        Method createProfile = ProfileMcpAdapter.class.getDeclaredMethod(
                "createProfile",
                ProfileWriteRequest.class
        );
        Method updateProfile = ProfileMcpAdapter.class.getDeclaredMethod(
                "updateProfile",
                UUID.class,
                ProfileWriteRequest.class
        );

        assertEquals("create_profile", createProfile.getAnnotation(McpTool.class).name());
        assertEquals("update_profile", updateProfile.getAnnotation(McpTool.class).name());
        assertEquals(1, createProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[1].length);
    }

    @Test
    void listProfilesToolDelegatesToService() {
        UserProfile profile = sampleProfile();
        when(profileService.listProfiles()).thenReturn(List.of(profile));

        assertEquals(List.of(profile), adapter.listProfiles());

        verify(profileService).listProfiles();
    }

    @Test
    void getProfileToolReturnsExistingAggregate() {
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.getProfile(PROFILE_ID)).thenReturn(Optional.of(aggregate));

        assertSame(aggregate, adapter.getProfile(PROFILE_ID));

        verify(profileService).getProfile(PROFILE_ID);
    }

    @Test
    void getProfileToolThrowsWhenMissing() {
        when(profileService.getProfile(PROFILE_ID)).thenReturn(Optional.empty());

        ProfileService.ProfileNotFoundException exception = assertThrows(
                ProfileService.ProfileNotFoundException.class,
                () -> adapter.getProfile(PROFILE_ID)
        );

        assertEquals("not_found", exception.errorCode().code());
        assertEquals(Map.of("resource", "profile", "profileId", PROFILE_ID.toString()), exception.details());
        verify(profileService).getProfile(PROFILE_ID);
    }

    @Test
    void createProfileToolDelegatesAndReturnsCreatedAggregate() {
        ProfileWriteRequest request = sampleRequest();
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.createProfile(request)).thenReturn(aggregate);

        assertSame(aggregate, adapter.createProfile(request));

        verify(profileService).createProfile(request);
    }

    @Test
    void updateProfileToolDelegatesAndReturnsUpdatedAggregate() {
        ProfileWriteRequest request = sampleRequest();
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.updateProfile(PROFILE_ID, request)).thenReturn(aggregate);

        assertSame(aggregate, adapter.updateProfile(PROFILE_ID, request));

        verify(profileService).updateProfile(PROFILE_ID, request);
    }

    @Test
    void deleteProfileToolReturnsDeletedResult() {
        when(profileService.deleteProfile(PROFILE_ID)).thenReturn(true);

        ProfileMcpAdapter.DeleteProfileResult result = adapter.deleteProfile(PROFILE_ID);

        assertEquals(PROFILE_ID, result.profileId());
        assertTrue(result.deleted());
        verify(profileService).deleteProfile(PROFILE_ID);
    }

    private static ProfileAggregate sampleAggregate() {
        return new ProfileAggregate(sampleProfile(), null, null, null, null, null, null, null);
    }

    private static UserProfile sampleProfile() {
        return new UserProfile(
                PROFILE_ID,
                "Agentic Dev",
                "agentic@example.test",
                "Builds MCP-native systems",
                null,
                NOW,
                NOW,
                null
        );
    }

    private static ProfileWriteRequest sampleRequest() {
        return new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.test",
                "Builds MCP-native systems",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

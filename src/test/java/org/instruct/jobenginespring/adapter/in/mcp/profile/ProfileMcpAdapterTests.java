package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
                Long.class,
                ProfileWriteRequest.class
        );

        assertEquals("create_profile", createProfile.getAnnotation(McpTool.class).name());
        assertEquals("update_profile", updateProfile.getAnnotation(McpTool.class).name());
        assertEquals(1, createProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[1].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[2].length);
    }

    @Test
    void listProfilesToolDelegatesToService() {
        UserProfile profile = sampleProfile();
        when(profileService.listProfiles()).thenReturn(List.of(profile));

        CallToolResult result = adapter.listProfiles();

        assertFalse(result.isError());
        ProfileMcpAdapter.ListProfilesResult listResult = assertInstanceOf(
                ProfileMcpAdapter.ListProfilesResult.class,
                result.structuredContent()
        );
        assertEquals(List.of(profile), listResult.profiles());
        verify(profileService).listProfiles();
    }

    @Test
    void getProfileToolReturnsExistingAggregate() {
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.getProfile(PROFILE_ID)).thenReturn(Optional.of(aggregate));

        CallToolResult result = adapter.getProfile(PROFILE_ID);

        assertSuccessfulContent(aggregate, result);
        verify(profileService).getProfile(PROFILE_ID);
    }

    @Test
    void getProfileToolReturnsSanitizedNotFoundErrorWhenMissing() {
        when(profileService.getProfile(PROFILE_ID)).thenReturn(Optional.empty());

        CallToolResult result = adapter.getProfile(PROFILE_ID);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("not_found", response.code());
        assertEquals("Profile not found: " + PROFILE_ID, response.message());
        assertEquals(Map.of("resource", "profile", "profileId", PROFILE_ID.toString()), response.details());
        verify(profileService).getProfile(PROFILE_ID);
    }

    @Test
    void createProfileToolDelegatesAndReturnsCreatedAggregate() {
        ProfileWriteRequest request = sampleRequest();
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.createProfile(request)).thenReturn(aggregate);

        CallToolResult result = adapter.createProfile(request);

        assertSuccessfulContent(aggregate, result);
        verify(profileService).createProfile(request);
    }

    @Test
    void updateProfileToolDelegatesAndReturnsUpdatedAggregate() {
        ProfileWriteRequest request = sampleRequest();
        ProfileAggregate aggregate = sampleAggregate();
        when(profileService.updateProfile(PROFILE_ID, 0L, request)).thenReturn(aggregate);

        CallToolResult result = adapter.updateProfile(PROFILE_ID, 0L, request);

        assertSuccessfulContent(aggregate, result);
        verify(profileService).updateProfile(PROFILE_ID, 0L, request);
    }

    @Test
    void deleteProfileToolReturnsDeletedResult() {
        when(profileService.deleteProfile(PROFILE_ID)).thenReturn(true);

        CallToolResult result = adapter.deleteProfile(PROFILE_ID);

        assertFalse(result.isError());
        ProfileMcpAdapter.DeleteProfileResult deleteResult = assertInstanceOf(
                ProfileMcpAdapter.DeleteProfileResult.class,
                result.structuredContent()
        );
        assertEquals(PROFILE_ID, deleteResult.profileId());
        assertTrue(deleteResult.deleted());
        verify(profileService).deleteProfile(PROFILE_ID);
    }

    @Test
    void writeToolsReturnSanitizedValidationErrors() {
        ProfileWriteRequest request = sampleRequest();
        ApplicationException validationFailure = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile write request",
                Map.of("field", "email", "reason", "must be a valid email address"),
                null
        );
        when(profileService.createProfile(request)).thenThrow(validationFailure);

        CallToolResult result = adapter.createProfile(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid profile write request", response.message());
        assertEquals(Map.of("field", "email", "reason", "must be a valid email address"), response.details());
    }

    @Test
    void toolErrorsDoNotExposeUnexpectedExceptionMessages() {
        when(profileService.listProfiles()).thenThrow(new RuntimeException("sensitive database detail"));

        CallToolResult result = adapter.listProfiles();

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    private static void assertSuccessfulContent(Object expected, CallToolResult result) {
        assertFalse(result.isError());
        assertEquals(expected, result.structuredContent());
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
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

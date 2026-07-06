package org.instruct.jobenginespring.adapter.in.mcp.profile;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileSearchService;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchMatch;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchRequest;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchResult;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileSearchMcpAdapterTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-07-06T18:30:00Z");

    private final ProfileSearchService profileSearchService = mock(ProfileSearchService.class);
    private final ProfileSearchMcpAdapter adapter = new ProfileSearchMcpAdapter(profileSearchService);

    @Test
    void exposesStableSearchProfilesToolNameAndParameter() throws NoSuchMethodException {
        Method searchProfiles = ProfileSearchMcpAdapter.class.getDeclaredMethod("searchProfiles", ProfileSearchRequest.class);

        assertEquals("search_profiles", searchProfiles.getAnnotation(McpTool.class).name());
        assertEquals(1, searchProfiles.getParameterAnnotations()[0].length);
    }

    @Test
    void searchProfilesToolDelegatesToService() {
        ProfileSearchRequest request = new ProfileSearchRequest("java", 5);
        ProfileSearchResult serviceResult = new ProfileSearchResult(
                "java",
                List.of("java"),
                1,
                List.of(new ProfileSearchMatch(sampleProfile(), 7, List.of("skills")))
        );
        when(profileSearchService.searchProfiles(request)).thenReturn(serviceResult);

        CallToolResult result = adapter.searchProfiles(request);

        assertFalse(result.isError());
        assertEquals(serviceResult, result.structuredContent());
        verify(profileSearchService).searchProfiles(request);
    }

    @Test
    void searchProfilesToolReturnsSanitizedValidationErrors() {
        ProfileSearchRequest request = new ProfileSearchRequest(" ", 5);
        ApplicationException validationFailure = new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile search request",
                Map.of("field", "query", "reason", "must not be blank"),
                null
        );
        when(profileSearchService.searchProfiles(request)).thenThrow(validationFailure);

        CallToolResult result = adapter.searchProfiles(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("validation_error", response.code());
        assertEquals("Invalid profile search request", response.message());
        assertEquals(Map.of("field", "query", "reason", "must not be blank"), response.details());
    }

    @Test
    void searchProfilesToolDoesNotExposeUnexpectedExceptionMessages() {
        ProfileSearchRequest request = new ProfileSearchRequest("java", 5);
        when(profileSearchService.searchProfiles(request)).thenThrow(new RuntimeException("private database detail"));

        CallToolResult result = adapter.searchProfiles(request);

        ApplicationErrorResponse response = assertErrorResponse(result);
        assertEquals("internal_error", response.code());
        assertEquals("Unexpected application error", response.message());
        assertEquals(Map.of(), response.details());
    }

    private static ApplicationErrorResponse assertErrorResponse(CallToolResult result) {
        assertTrue(result.isError());
        return assertInstanceOf(ApplicationErrorResponse.class, result.structuredContent());
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
}

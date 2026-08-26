package org.instruct.jobenginespring.adapter.in.http.operator;

import tools.jackson.databind.ObjectMapper;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.profile.ProfileSearchService;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchRequest;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchResult;
import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectUpdateRequest;
import org.instruct.jobenginespring.application.profile.ProjectUpdateResult;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileOperatorControllerTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProfileService profileService = mock(ProfileService.class);
    private final ProfileSearchService profileSearchService = mock(ProfileSearchService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProfileOperatorController(profileService, profileSearchService))
            .setControllerAdvice(new OperatorProblemHandler())
            .build();

    @Test
    void listsProfilesWithBoundedPage() throws Exception {
        UserProfile profile = userProfile();
        when(profileService.listProfiles(5, "cursor-1")).thenReturn(new Page<>(List.of(profile), "cursor-2"));

        mvc.perform(get("/api/operator/v1/profiles").param("limit", "5").param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[0].id").value(profile.id().toString()))
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"));
    }

    @Test
    void listsProfilesWithoutLimitOrCursor() throws Exception {
        when(profileService.listProfiles(null, null)).thenReturn(new Page<>(List.of(), null));

        mvc.perform(get("/api/operator/v1/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles").isEmpty());
    }

    @Test
    void searchesProfilesByQuery() throws Exception {
        ProfileSearchResult result = new ProfileSearchResult("java", List.of("java"), 1, 1, false, 1, List.of());
        when(profileSearchService.searchProfiles(new ProfileSearchRequest("java", 10))).thenReturn(result);

        mvc.perform(get("/api/operator/v1/profiles/search").param("query", "java").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("java"))
                .andExpect(jsonPath("$.matchedCount").value(1));
    }

    @Test
    void rejectsBlankSearchQueryAsSanitizedValidationError() throws Exception {
        when(profileSearchService.searchProfiles(any())).thenThrow(
                new ApplicationException(ApplicationErrorCode.VALIDATION_ERROR, "Invalid profile search request",
                        java.util.Map.of("field", "query", "reason", "must not be blank"), null));

        mvc.perform(get("/api/operator/v1/profiles/search").param("query", " "))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.detail").value(ApplicationErrorCode.VALIDATION_ERROR.defaultMessage()));
    }

    @Test
    void getsProfileByIdSuccessfully() throws Exception {
        UUID profileId = UUID.randomUUID();
        ProfileAggregate aggregate = new ProfileAggregate(
                userProfile(profileId), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(profileService.getProfile(profileId)).thenReturn(Optional.of(aggregate));

        mvc.perform(get("/api/operator/v1/profiles/{profileId}", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value(profileId.toString()));
    }

    @Test
    void returnsNotFoundForMissingProfile() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(profileService.getProfile(profileId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/operator/v1/profiles/{profileId}", profileId))
                .andExpect(status().isNotFound())
                .andExpect(header().string(org.springframework.http.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void createsProfileAndReturns201() throws Exception {
        ProfileWriteRequest request = writeRequest();
        ProfileAggregate created = new ProfileAggregate(
                userProfile(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(profileService.createProfile(request)).thenReturn(created);

        mvc.perform(post("/api/operator/v1/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.fullName").value("Ada Lovelace"));
    }

    @Test
    void updatesProfileWithExpectedRevision() throws Exception {
        UUID profileId = UUID.randomUUID();
        ProfileWriteRequest request = writeRequest();
        ProfileAggregate updated = new ProfileAggregate(
                userProfile(profileId), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(profileService.updateProfile(profileId, 3L, request)).thenReturn(updated);

        mvc.perform(put("/api/operator/v1/profiles/{profileId}", profileId)
                        .param("expectedRevision", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value(profileId.toString()));
    }

    @Test
    void returnsConflictWhenUpdateRevisionIsStale() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(profileService.updateProfile(eq(profileId), eq(1L), any())).thenThrow(
                new ApplicationException(ApplicationErrorCode.CONFLICT, "Profile revision conflict",
                        java.util.Map.of("resource", "profile"), null));

        mvc.perform(put("/api/operator/v1/profiles/{profileId}", profileId)
                        .param("expectedRevision", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(writeRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void patchesProjectPartially() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ProjectUpdateRequest expectedServiceRequest = new ProjectUpdateRequest(
                profileId, projectId, 2L, "New Name", null, null, null, null
        );
        ProfileProject project = new ProfileProject(
                projectId, profileId, "New Name", null, null, List.of(), 0, Instant.parse("2026-01-01T00:00:00Z")
        );
        when(profileService.updateProject(expectedServiceRequest)).thenReturn(new ProjectUpdateResult(project, 3L));

        mvc.perform(patch("/api/operator/v1/profiles/{profileId}/projects/{projectId}", profileId, projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.name").value("New Name"))
                .andExpect(jsonPath("$.profileRevision").value(3));
    }

    @Test
    void returnsNotFoundWhenPatchingMissingProject() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(profileService.updateProject(any())).thenThrow(
                new ApplicationException(ApplicationErrorCode.NOT_FOUND, "Profile project not found",
                        java.util.Map.of("resource", "project"), null));

        mvc.perform(patch("/api/operator/v1/profiles/{profileId}/projects/{projectId}", profileId, projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesProfileAndReportsOutcome() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(profileService.deleteProfile(profileId)).thenReturn(true);

        mvc.perform(delete("/api/operator/v1/profiles/{profileId}", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(profileId.toString()))
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void reportsFalseWhenDeletingMissingProfileWithoutError() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(profileService.deleteProfile(profileId)).thenReturn(false);

        mvc.perform(delete("/api/operator/v1/profiles/{profileId}", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));
    }

    private static UserProfile userProfile() {
        return userProfile(UUID.randomUUID());
    }

    private static UserProfile userProfile(UUID id) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new UserProfile(id, "Ada Lovelace", "ada@example.com", "Summary", null, now, now, null, 0);
    }

    private static ProfileWriteRequest writeRequest() {
        return new ProfileWriteRequest(
                "Ada Lovelace", "ada@example.com", "Summary",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}

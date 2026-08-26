package org.instruct.jobenginespring.adapter.in.http.operator;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.ProfileSearchService;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchRequest;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchResult;
import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectUpdateRequest;
import org.instruct.jobenginespring.application.profile.ProjectUpdateResult;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator/v1/profiles")
@RequiredArgsConstructor
class ProfileOperatorController {

    private final ProfileService profileService;
    private final ProfileSearchService profileSearchService;

    @GetMapping
    ProfileListResponse listProfiles(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        var page = profileService.listProfiles(limit, cursor);
        return new ProfileListResponse(page.items(), page.nextCursor());
    }

    @GetMapping("/search")
    ProfileSearchResult searchProfiles(
            @RequestParam String query,
            @RequestParam(required = false) Integer limit
    ) {
        return profileSearchService.searchProfiles(new ProfileSearchRequest(query, limit));
    }

    @GetMapping("/{profileId}")
    ProfileAggregate getProfile(@PathVariable UUID profileId) {
        return profileService.getProfile(profileId).orElseThrow(() -> new ProfileNotFoundException(profileId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProfileAggregate createProfile(@RequestBody ProfileWriteRequest request) {
        return profileService.createProfile(request);
    }

    @PutMapping("/{profileId}")
    ProfileAggregate updateProfile(
            @PathVariable UUID profileId,
            @RequestParam Long expectedRevision,
            @RequestBody ProfileWriteRequest request
    ) {
        return profileService.updateProfile(profileId, expectedRevision, request);
    }

    @PatchMapping("/{profileId}/projects/{projectId}")
    ProjectUpdateResult updateProject(
            @PathVariable UUID profileId,
            @PathVariable UUID projectId,
            @RequestBody ProjectPatchRequest request
    ) {
        return profileService.updateProject(new ProjectUpdateRequest(
                profileId,
                projectId,
                request.expectedRevision(),
                request.name(),
                request.url(),
                request.description(),
                request.displayOrder(),
                request.technologies()
        ));
    }

    @DeleteMapping("/{profileId}")
    DeleteProfileResponse deleteProfile(@PathVariable UUID profileId) {
        return new DeleteProfileResponse(profileId, profileService.deleteProfile(profileId));
    }

    record ProfileListResponse(List<UserProfile> profiles, String nextCursor) {
    }

    record DeleteProfileResponse(UUID profileId, boolean deleted) {
    }

    record ProjectPatchRequest(
            Long expectedRevision,
            String name,
            String url,
            String description,
            Integer displayOrder,
            List<ProjectTechnologyWriteRequest> technologies
    ) {
    }
}

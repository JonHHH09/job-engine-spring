package org.instruct.jobenginespring.adapter.in.http.operator;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.ProfilePdfIngestionResult;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/operator/v1/profiles")
@RequiredArgsConstructor
class ProfilePdfIngestionOperatorController {

    private final ProfilePdfIngestionService profilePdfIngestionService;

    @PostMapping("/pdf-ingestions")
    ProfilePdfIngestionResult ingestProfileFromStoredPdf(@RequestBody IngestProfileFromStoredPdfRequest request) {
        return profilePdfIngestionService.ingestProfileFromStoredPdf(request);
    }

    @GetMapping("/{profileId}/pdf-source")
    ProfilePdfSource getProfilePdfSource(@PathVariable UUID profileId) {
        return profilePdfIngestionService.getProfilePdfSource(profileId);
    }
}

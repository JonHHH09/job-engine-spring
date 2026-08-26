package org.instruct.jobenginespring.adapter.in.http.operator;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.ProfilePdfIngestionResult;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfilePdfIngestionOperatorControllerTests {

    private final ProfilePdfIngestionService profilePdfIngestionService = mock(ProfilePdfIngestionService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProfilePdfIngestionOperatorController(profilePdfIngestionService))
            .setControllerAdvice(new OperatorProblemHandler())
            .build();

    @Test
    void ingestsProfileFromStoredPdf() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        IngestProfileFromStoredPdfRequest expectedRequest =
                new IngestProfileFromStoredPdfRequest(documentId, null, null, null, null);
        ProfilePdfIngestionResult result = new ProfilePdfIngestionResult(
                IngestionStatus.CREATED_PROFILE, profileId, documentId, UUID.randomUUID(), UUID.randomUUID(),
                "resume.pdf", 2, 1200, false, true, false, null, List.of(), null
        );
        when(profilePdfIngestionService.ingestProfileFromStoredPdf(expectedRequest)).thenReturn(result);

        mvc.perform(post("/api/operator/v1/profiles/pdf-ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED_PROFILE"))
                .andExpect(jsonPath("$.profileId").value(profileId.toString()));
    }

    @Test
    void rejectsIngestionForMissingDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(profilePdfIngestionService.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(documentId, null, null, null, null)
        )).thenThrow(new ApplicationException(
                ApplicationErrorCode.NOT_FOUND, "Stored document was not found", Map.of("documentId", documentId.toString()), null
        ));

        mvc.perform(post("/api/operator/v1/profiles/pdf-ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getsProfilePdfSource() throws Exception {
        UUID profileId = UUID.randomUUID();
        ProfilePdfSource source = new ProfilePdfSource(
                UUID.randomUUID(), profileId, UUID.randomUUID(), "STORED_PDF", Instant.parse("2026-01-01T00:00:00Z")
        );
        when(profilePdfIngestionService.getProfilePdfSource(profileId)).thenReturn(source);

        mvc.perform(get("/api/operator/v1/profiles/{profileId}/pdf-source", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(profileId.toString()));
    }

    @Test
    void returnsNotFoundWhenNoPdfSourceIsLinked() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(profilePdfIngestionService.getProfilePdfSource(profileId)).thenThrow(new ApplicationException(
                ApplicationErrorCode.NOT_FOUND, "Profile PDF source was not found", Map.of("profileId", profileId.toString()), null
        ));

        mvc.perform(get("/api/operator/v1/profiles/{profileId}/pdf-source", profileId))
                .andExpect(status().isNotFound());
    }
}

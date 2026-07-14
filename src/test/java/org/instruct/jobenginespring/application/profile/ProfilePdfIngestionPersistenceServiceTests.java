package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfilePdfIngestionPersistenceServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void databaseRaceReadBackReusesExtractionOrDocumentHash() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfilePdfIngestionPersistenceService service = service(sources);
        StoredPdfTextExtractionResult extraction = extraction();
        ProfilePdfSource source = new ProfilePdfSource(
                UUID.randomUUID(), UUID.randomUUID(), extraction.extractionId(), "resume_pdf", NOW
        );
        when(sources.findByPdfExtractionId(extraction.extractionId())).thenReturn(Optional.of(source));

        var byExtraction = service.persist(request(), extraction, null);
        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, byExtraction.status());

        when(sources.findByPdfExtractionId(extraction.extractionId())).thenReturn(Optional.empty());
        when(sources.findByDocumentSha256(extraction.document().sha256())).thenReturn(Optional.of(source));
        var byHash = service.persist(request(), extraction, null);
        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, byHash.status());
    }

    @Test
    void transactionRechecksExistingProfileLinkAndValidatesConstruction() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfilePdfIngestionPersistenceService service = service(sources);
        StoredPdfTextExtractionResult extraction = extraction();
        UUID profileId = UUID.randomUUID();
        when(sources.findByPdfExtractionId(extraction.extractionId())).thenReturn(Optional.empty());
        when(sources.findByDocumentSha256(extraction.document().sha256())).thenReturn(Optional.empty());
        when(sources.findByProfileId(profileId)).thenReturn(Optional.of(new ProfilePdfSource(
                UUID.randomUUID(), profileId, UUID.randomUUID(), "resume_pdf", NOW
        )));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.persist(
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), profileId, true, null),
                extraction,
                null
        ));
        assertThrows(NullPointerException.class, () -> new ProfilePdfIngestionPersistenceService(
                null, mock(ProfileIdentityMatcher.class), sources, Clock.systemUTC()
        ));
        assertThrows(NullPointerException.class, () -> new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class), null, sources, Clock.systemUTC()
        ));
        assertThrows(NullPointerException.class, () -> new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class), mock(ProfileIdentityMatcher.class), null, Clock.systemUTC()
        ));
        assertThrows(NullPointerException.class, () -> new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class), mock(ProfileIdentityMatcher.class), sources, null
        ));
        new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class), mock(ProfileIdentityMatcher.class), sources
        );
        StoredPdfTextExtractionResult missingExtractionId = new StoredPdfTextExtractionResult(
                extraction.document(), null, extraction.extraction()
        );
        assertThrows(NullPointerException.class, () -> service.persist(request(), missingExtractionId, null));
    }

    private static ProfilePdfIngestionPersistenceService service(ProfilePdfSourceRepository sources) {
        return new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class),
                mock(ProfileIdentityMatcher.class),
                sources,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static IngestProfileFromStoredPdfRequest request() {
        return new IngestProfileFromStoredPdfRequest(UUID.randomUUID(), null, null, null);
    }

    private static StoredPdfTextExtractionResult extraction() {
        UUID documentId = UUID.randomUUID();
        return new StoredPdfTextExtractionResult(
                new StoredDocumentMetadata(documentId, "resume.pdf", "application/pdf", 10, "sha", NOW, NOW),
                UUID.randomUUID(),
                new PdfTextExtractionResult("resume.pdf", 1, 4, false, "text", List.of())
        );
    }
}

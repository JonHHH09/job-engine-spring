package org.instruct.jobenginespring.application.profile;

import org.apache.commons.codec.digest.DigestUtils;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository.LinkedPdfSource;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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
        when(sources.findLinkedByPdfExtractionId(extraction.extractionId()))
                .thenReturn(Optional.of(linked(source, extraction)));

        var byExtraction = service.persist(request(), extraction, null);
        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, byExtraction.status());

        when(sources.findLinkedByPdfExtractionId(extraction.extractionId())).thenReturn(Optional.empty());
        when(sources.findLinkedByDocumentSha256(extraction.document().sha256()))
                .thenReturn(Optional.of(linked(source, extraction)));
        var byHash = service.persist(request(), extraction, null);
        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, byHash.status());
    }

    @Test
    void transactionRechecksExistingProfileLinkAndValidatesConstruction() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfilePdfIngestionPersistenceService service = service(sources);
        StoredPdfTextExtractionResult extraction = extraction();
        UUID profileId = UUID.randomUUID();
        when(sources.findLinkedByPdfExtractionId(extraction.extractionId())).thenReturn(Optional.empty());
        when(sources.findLinkedByDocumentSha256(extraction.document().sha256())).thenReturn(Optional.empty());
        ProfilePdfSource existing = new ProfilePdfSource(
                UUID.randomUUID(), profileId, UUID.randomUUID(), "resume_pdf", NOW
        );
        when(sources.findLinkedByProfileId(profileId)).thenReturn(Optional.of(linked(existing, extraction)));

        var duplicate = service.persist(
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), profileId, true, null),
                extraction,
                writeRequest("candidate@example.test")
        );
        assertEquals(IngestionStatus.DUPLICATE_PROFILE_CANDIDATE, duplicate.status());
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

    @Test
    void concurrentSourceWinnerIsReusedAndNewLosingProfileIsRemoved() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfileService profiles = mock(ProfileService.class);
        ProfileIdentityMatcher matcher = mock(ProfileIdentityMatcher.class);
        ProfilePdfIngestionPersistenceService service = new ProfilePdfIngestionPersistenceService(
                profiles, matcher, sources, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        StoredPdfTextExtractionResult extraction = extraction();
        UUID loserProfileId = UUID.randomUUID();
        UUID winnerProfileId = UUID.randomUUID();
        ProfileAggregate loser = aggregate(loserProfileId);
        ProfilePdfSource winner = new ProfilePdfSource(
                UUID.randomUUID(), winnerProfileId, extraction.extractionId(), "resume_pdf", NOW
        );
        when(matcher.findStrongMatch(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(profiles.createProfile(org.mockito.ArgumentMatchers.any())).thenReturn(loser);
        when(sources.insertOrFind(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ProfilePdfSourceRepository.InsertResult(winner, false)
        );

        var result = service.persist(request(), extraction, writeRequest("loser@example.test"));

        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, result.status());
        assertEquals(winnerProfileId, result.profileId());
        verify(profiles).deleteProfile(loserProfileId);
    }

    @Test
    void profileUniquenessConflictRemainsAValidationError() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfileService profiles = mock(ProfileService.class);
        ProfileIdentityMatcher matcher = mock(ProfileIdentityMatcher.class);
        ProfilePdfIngestionPersistenceService service = new ProfilePdfIngestionPersistenceService(
                profiles, matcher, sources, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        StoredPdfTextExtractionResult extraction = extraction();
        UUID profileId = UUID.randomUUID();
        ProfileAggregate created = aggregate(profileId);
        ProfilePdfSource existingProfileSource = new ProfilePdfSource(
                UUID.randomUUID(), profileId, UUID.randomUUID(), "resume_pdf", NOW
        );
        when(matcher.findStrongMatch(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(profiles.createProfile(org.mockito.ArgumentMatchers.any())).thenReturn(created);
        when(sources.insertOrFind(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ProfilePdfSourceRepository.InsertResult(existingProfileSource, false)
        );

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class,
                () -> service.persist(request(), extraction, writeRequest("conflict@example.test")));
        verify(profiles).deleteProfile(profileId);
    }

    @Test
    void extractionRaceAgainstAnotherExistingProfileRollsBackTheUpdate() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfileService profiles = mock(ProfileService.class);
        ProfileIdentityMatcher matcher = mock(ProfileIdentityMatcher.class);
        ProfilePdfIngestionPersistenceService service = new ProfilePdfIngestionPersistenceService(
                profiles, matcher, sources, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        StoredPdfTextExtractionResult extraction = extraction();
        UUID updatedProfileId = UUID.randomUUID();
        ProfileAggregate updated = aggregate(updatedProfileId);
        ProfilePdfSource winner = new ProfilePdfSource(
                UUID.randomUUID(), UUID.randomUUID(), extraction.extractionId(), "resume_pdf", NOW
        );
        when(profiles.updateProfile(
                org.mockito.ArgumentMatchers.eq(updatedProfileId),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(updated);
        when(sources.insertOrFind(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ProfilePdfSourceRepository.InsertResult(winner, false)
        );

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.persist(
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), updatedProfileId, true, null, 0L),
                extraction,
                writeRequest("updated@example.test")
        ));
        verify(profiles, never()).deleteProfile(updatedProfileId);
    }

    @Test
    void concurrentExactLinkForUpdatedProfileIsReused() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfileService profiles = mock(ProfileService.class);
        ProfileIdentityMatcher matcher = mock(ProfileIdentityMatcher.class);
        ProfilePdfIngestionPersistenceService service = new ProfilePdfIngestionPersistenceService(
                profiles, matcher, sources, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        StoredPdfTextExtractionResult extraction = extraction();
        UUID profileId = UUID.randomUUID();
        ProfileAggregate updated = aggregate(profileId);
        ProfilePdfSource winner = new ProfilePdfSource(
                UUID.randomUUID(), profileId, extraction.extractionId(), "resume_pdf", NOW
        );
        when(profiles.updateProfile(
                org.mockito.ArgumentMatchers.eq(profileId),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(updated);
        when(sources.insertOrFind(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ProfilePdfSourceRepository.InsertResult(winner, false)
        );

        var result = service.persist(
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), profileId, true, null, 0L),
                extraction,
                writeRequest("updated@example.test")
        );

        assertEquals(IngestionStatus.REUSED_EXISTING_SOURCE, result.status());
        assertEquals(profileId, result.profileId());
        verify(profiles, never()).deleteProfile(profileId);
    }

    @Test
    void locksDocumentIdentityAndTargetProfileInsidePersistenceBoundary() {
        ProfilePdfSourceRepository sources = mock(ProfilePdfSourceRepository.class);
        ProfileService profiles = mock(ProfileService.class);
        ProfileIdentityMatcher matcher = mock(ProfileIdentityMatcher.class);
        ProfilePdfIngestionPersistenceService service = new ProfilePdfIngestionPersistenceService(
                profiles, matcher, sources, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        StoredPdfTextExtractionResult extraction = extraction();
        UUID profileId = UUID.randomUUID();
        var writeRequest = writeRequest("updated@example.test");
        ProfilePdfSource inserted = new ProfilePdfSource(
                UUID.randomUUID(), profileId, extraction.extractionId(), "resume_pdf", NOW
        );
        when(matcher.concurrencyKeys(writeRequest)).thenReturn(List.of("identity-a", "identity-b"));
        when(profiles.updateProfile(profileId, 0L, writeRequest)).thenReturn(aggregate(profileId));
        when(sources.insertOrFind(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ProfilePdfSourceRepository.InsertResult(inserted, true));

        service.persist(
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), profileId, true, null, 0L),
                extraction,
                writeRequest
        );

        var ordered = inOrder(sources, matcher, profiles);
        ordered.verify(sources).acquireIngestionLock(DigestUtils.sha256Hex(
                "document:" + extraction.document().sha256()
        ));
        ordered.verify(sources).findLinkedByPdfExtractionId(extraction.extractionId());
        ordered.verify(sources).findLinkedByDocumentSha256(extraction.document().sha256());
        ordered.verify(matcher).concurrencyKeys(writeRequest);
        ordered.verify(sources).acquireIngestionLock("identity-a");
        ordered.verify(sources).acquireIngestionLock("identity-b");
        ordered.verify(sources).acquireIngestionLock(DigestUtils.sha256Hex("profile:" + profileId));
        ordered.verify(sources).findLinkedByProfileId(profileId);
        ordered.verify(profiles).updateProfile(profileId, 0L, writeRequest);
        ordered.verify(sources).insertOrFind(org.mockito.ArgumentMatchers.any());
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

    private static ProfileAggregate aggregate(UUID profileId) {
        return new ProfileAggregate(
                new UserProfile(profileId, "Candidate", "candidate@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static LinkedPdfSource linked(
            ProfilePdfSource source,
            StoredPdfTextExtractionResult extraction
    ) {
        return new LinkedPdfSource(
                source,
                extraction.document().id(),
                extraction.document().originalFileName(),
                extraction.extraction().pageCount(),
                extraction.extraction().characterCount(),
                extraction.extraction().truncated()
        );
    }

    private static ProfileService.ProfileWriteRequest writeRequest(String email) {
        return new ProfileService.ProfileWriteRequest(
                "Candidate", email, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}

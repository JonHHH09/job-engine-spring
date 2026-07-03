package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.ExtractStoredPdfTextRequest;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfilePdfIngestionServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-03T18:30:00Z");
    private static final UUID DOCUMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EXTRACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PROFILE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SOURCE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private final DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
    private final ProfileTextExtractor profileTextExtractor = mock(ProfileTextExtractor.class);
    private final ProfileService profileService = mock(ProfileService.class);
    private final InMemoryProfilePdfSourceRepository sourceRepository = new InMemoryProfilePdfSourceRepository();
    private final ProfilePdfIngestionService service = new ProfilePdfIngestionService(
            documentStorageService,
            profileTextExtractor,
            profileService,
            sourceRepository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void createsProfileAndSourceLinkFromStoredPdfExtraction() {
        StoredPdfTextExtractionResult storedExtraction = storedExtraction();
        ProfileWriteRequest writeRequest = new ProfileWriteRequest("Agentic Dev", "agentic@example.test", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(documentStorageService.extractStoredPdfText(new ExtractStoredPdfTextRequest(DOCUMENT_ID, 10_000, false, true)))
                .thenReturn(storedExtraction);
        when(profileTextExtractor.extractProfile(any())).thenReturn(writeRequest);
        when(profileService.createProfile(writeRequest)).thenReturn(profileAggregate(PROFILE_ID));

        ProfilePdfIngestionService.ProfilePdfIngestionResult result = service.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, null, null, 10_000)
        );

        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(DOCUMENT_ID, result.documentId());
        assertEquals(EXTRACTION_ID, result.pdfExtractionId());
        assertEquals("resume.pdf", result.originalFileName());
        assertEquals(1, result.pageCount());
        assertEquals(11, result.characterCount());
        assertTrue(result.createdProfile());
        assertFalse(result.existingProfileLink());
        assertEquals(1, sourceRepository.count());
        verify(profileService).createProfile(writeRequest);
    }

    @Test
    void repeatedIngestionReturnsExistingSourceLinkWithoutCreatingProfile() {
        ProfilePdfSource existing = new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", NOW);
        sourceRepository.save(existing);
        when(documentStorageService.extractStoredPdfText(new ExtractStoredPdfTextRequest(DOCUMENT_ID, null, false, true)))
                .thenReturn(storedExtraction());

        ProfilePdfIngestionService.ProfilePdfIngestionResult result = service.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, null, null, null)
        );

        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(SOURCE_ID, result.sourceLinkId());
        assertTrue(result.existingProfileLink());
        assertFalse(result.createdProfile());
        verify(profileTextExtractor, never()).extractProfile(any());
        verify(profileService, never()).createProfile(any());
    }

    @Test
    void rejectsExistingProfileWithoutOverwrite() {
        when(documentStorageService.extractStoredPdfText(new ExtractStoredPdfTextRequest(DOCUMENT_ID, null, false, true)))
                .thenReturn(storedExtraction());
        when(profileTextExtractor.extractProfile(any())).thenReturn(new ProfileWriteRequest("Agentic Dev", "agentic@example.test", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, PROFILE_ID, false, null)
        ));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(0, sourceRepository.count());
    }

    @Test
    void updatesExistingProfileWhenOverwriteIsExplicit() {
        ProfileWriteRequest writeRequest = new ProfileWriteRequest("Agentic Dev", "agentic@example.test", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(documentStorageService.extractStoredPdfText(new ExtractStoredPdfTextRequest(DOCUMENT_ID, null, false, true)))
                .thenReturn(storedExtraction());
        when(profileTextExtractor.extractProfile(any())).thenReturn(writeRequest);
        when(profileService.updateProfile(PROFILE_ID, writeRequest)).thenReturn(profileAggregate(PROFILE_ID));

        ProfilePdfIngestionService.ProfilePdfIngestionResult result = service.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, PROFILE_ID, true, null)
        );

        assertEquals(PROFILE_ID, result.profileId());
        assertFalse(result.createdProfile());
        assertFalse(result.existingProfileLink());
        verify(profileService).updateProfile(PROFILE_ID, writeRequest);
    }

    @Test
    void rejectsExistingProfileAlreadyLinkedToAnotherExtraction() {
        sourceRepository.save(new ProfilePdfSource(SOURCE_ID, PROFILE_ID, UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), "resume_pdf", NOW));
        when(documentStorageService.extractStoredPdfText(new ExtractStoredPdfTextRequest(DOCUMENT_ID, null, false, true)))
                .thenReturn(storedExtraction());

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.ingestProfileFromStoredPdf(
                new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, PROFILE_ID, true, null)
        ));

        assertEquals("validation_error", exception.errorCode().code());
        verify(profileTextExtractor, never()).extractProfile(any());
    }

    @Test
    void getsProfilePdfSourceByProfileId() {
        ProfilePdfSource source = new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", NOW);
        sourceRepository.save(source);

        assertEquals(source, service.getProfilePdfSource(PROFILE_ID));
    }

    @Test
    void reportsMissingProfilePdfSourceSafely() {
        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> service.getProfilePdfSource(PROFILE_ID)
        );

        assertEquals("not_found", exception.errorCode().code());
    }

    private static StoredPdfTextExtractionResult storedExtraction() {
        return new StoredPdfTextExtractionResult(
                new StoredDocumentMetadata(
                        DOCUMENT_ID,
                        "resume.pdf",
                        "application/pdf",
                        128,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        NOW,
                        NOW
                ),
                EXTRACTION_ID,
                new PdfTextExtractionResult("resume.pdf", 1, 11, false, "Joni Hysaj\nagentic@example.test", List.of())
        );
    }

    private static ProfileAggregate profileAggregate(UUID profileId) {
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static final class InMemoryProfilePdfSourceRepository implements ProfilePdfSourceRepository {

        private final java.util.Map<UUID, ProfilePdfSource> sourcesByProfileId = new java.util.LinkedHashMap<>();

        @Override
        public ProfilePdfSource save(ProfilePdfSource source) {
            sourcesByProfileId.put(source.profileId(), source.id() == null
                    ? new ProfilePdfSource(SOURCE_ID, source.profileId(), source.pdfExtractionId(), source.sourceType(), source.createdAt())
                    : source);
            return sourcesByProfileId.get(source.profileId());
        }

        @Override
        public Optional<ProfilePdfSource> findByProfileId(UUID profileId) {
            return Optional.ofNullable(sourcesByProfileId.get(profileId));
        }

        @Override
        public Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId) {
            return sourcesByProfileId.values().stream()
                    .filter(source -> source.pdfExtractionId().equals(pdfExtractionId))
                    .findFirst();
        }

        private int count() {
            return sourcesByProfileId.size();
        }
    }
}

package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobService.JobNotFoundException;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.port.ProfilePersonalDetailsRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.resume.OfflineGermanResumeTranslator;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ReplaceResult;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateGermanTailoredResumeServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @TempDir
    Path tempDir;

    private ProfileRepository profileRepository;
    private JobRepository jobRepository;
    private ProfilePersonalDetailsRepository personalDetailsRepository;
    private ResumeRepository resumeRepository;
    private DocumentStorageService documentStorageService;
    private DocumentRepository documentRepository;
    private GeneratedResumeFileRepository fileRepository;
    private GeneratedResumeCleanupService cleanupService;
    private TransactionLifecycle transactionLifecycle;
    private GenerateGermanTailoredResumeService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        jobRepository = mock(JobRepository.class);
        personalDetailsRepository = mock(ProfilePersonalDetailsRepository.class);
        resumeRepository = mock(ResumeRepository.class);
        documentStorageService = mock(DocumentStorageService.class);
        documentRepository = mock(DocumentRepository.class);
        fileRepository = mock(GeneratedResumeFileRepository.class);
        cleanupService = mock(GeneratedResumeCleanupService.class);
        transactionLifecycle = mock(TransactionLifecycle.class);
        service = new GenerateGermanTailoredResumeService(
                profileRepository,
                jobRepository,
                personalDetailsRepository,
                resumeRepository,
                documentStorageService,
                documentRepository,
                fileRepository,
                cleanupService,
                transactionLifecycle,
                new OfflineGermanResumeTranslator(),
                tempDir,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void generatesBilingualResumeAndReplacesPreviousDocuments() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation -> {
            UUID documentId = UUID.randomUUID();
            return new StoredDocumentMetadata(
                    documentId,
                    "resume.pdf",
                    DocumentStorageService.PDF_MEDIA_TYPE,
                    100L,
                    "sha-" + documentId,
                    NOW,
                    NOW
            );
        });
        when(resumeRepository.replaceGermanyResume(any())).thenAnswer(invocation -> {
            ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
            ResumeVariant previous = new ResumeVariant(
                    UUID.randomUUID(), write.resume().id(), "en", UUID.randomUUID(),
                    tempDir.resolve("old.pdf").toString(), NOW, NOW
            );
            return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of(previous));
        });

        GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeResult result = service.generate(
                new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)
        );

        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(JOB_ID, result.jobId());
        assertEquals(Resume.FORMAT_GERMANY, result.format());
        assertEquals(2, result.variants().size());
        assertTrue(result.replacedExisting());
        assertTrue(result.variants().stream().anyMatch(variant -> "en".equals(variant.language())));
        assertTrue(result.variants().stream().anyMatch(variant -> "de".equals(variant.language())));
        verify(documentRepository).deleteFileIfUnreferenced(any());
        verify(cleanupService).enqueueAfterCommit(any());
    }

    @Test
    void rejectsMissingProfile() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.empty());
        assertThrows(ProfileNotFoundException.class, () -> service.generate(
                new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)
        ));
    }

    @Test
    void rejectsMissingJob() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.generate(
                new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)
        ));
    }

    @Test
    void rejectsNullIds() {
        assertThrows(ApplicationException.class, () -> service.generate(
                new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(null, JOB_ID)
        ));
        assertThrows(ApplicationException.class, () -> service.generate(
                new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, null)
        ));
    }

    private static ProfileAggregate profile() {
        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Joni Hysaj", "joni@example.test", "Summary ignored", null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static JobAggregate job() {
        JobPosting posting = new JobPosting(
                JOB_ID, "text", "EY", "Digitalization", "EY", "Stuttgart",
                "Python data systems", null, null, null, null, "fp-1", NOW, NOW
        );
        return new JobAggregate(posting, List.of(), null, new JobTextIngestion(UUID.randomUUID(), JOB_ID, "label", "hash-1", NOW));
    }
}

package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateGermanCoverLetterServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID RESUME_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @TempDir
    Path tempDir;

    private ProfileRepository profiles;
    private JobRepository jobs;
    private ResumeRepository resumes;
    private GermanCoverLetterPersistenceService persistence;
    private DocumentStorageService documents;
    private GeneratedResumeCleanupService cleanup;
    private GenerateGermanCoverLetterService service;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileRepository.class);
        jobs = mock(JobRepository.class);
        resumes = mock(ResumeRepository.class);
        persistence = mock(GermanCoverLetterPersistenceService.class);
        documents = mock(DocumentStorageService.class);
        cleanup = mock(GeneratedResumeCleanupService.class);
        service = new GenerateGermanCoverLetterService(
                profiles, jobs, resumes, persistence, documents, cleanup, tempDir,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(profiles.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(jobs.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(resume(Resume.FORMAT_GERMANY)));
        when(documents.storeGeneratedDocumentFile(any(Path.class), anyString())).thenReturn(
                new StoredDocumentMetadata(UUID.randomUUID(), "letter.pdf", DocumentStorageService.PDF_MEDIA_TYPE,
                        1234L, "synthetic-sha", NOW, NOW));
        when(persistence.replace(any(), any())).thenAnswer(invocation -> {
            CoverLetterRepository.CoverLetterAggregateWrite write = invocation.getArgument(0);
            return new CoverLetterRepository.ReplaceResult(write.coverLetter(), write.variant().variant(), List.of());
        });
    }

    @Test
    void generatesAndPersistsOneGermanVariantForExactResume() {
        GenerateGermanCoverLetterService.GenerateGermanCoverLetterResult result = service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        );

        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(JOB_ID, result.jobId());
        assertEquals(RESUME_ID, result.resumeId());
        assertEquals("germany", result.format());
        assertEquals("de", result.language());
        assertEquals(1, result.pageCount());
        ArgumentCaptor<CoverLetterRepository.CoverLetterAggregateWrite> write =
                ArgumentCaptor.forClass(CoverLetterRepository.CoverLetterAggregateWrite.class);
        verify(persistence).replace(write.capture(), any());
        assertEquals(RESUME_ID, write.getValue().coverLetter().resumeId());
        assertTrue(write.getValue().variant().paragraphs().size() >= 3);
        assertTrue(write.getValue().variant().variant().subject().contains("Backend Engineer"));
    }

    @Test
    void reportsReplacementWhenAPreviousVariantExists() {
        doAnswer(invocation -> {
            CoverLetterRepository.CoverLetterAggregateWrite write = invocation.getArgument(0);
            return new CoverLetterRepository.ReplaceResult(
                    write.coverLetter(), write.variant().variant(), List.of(write.variant().variant())
            );
        }).when(persistence).replace(any(), any());

        var result = service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        );

        assertTrue(result.replacedExisting());
    }

    @Test
    void reportsEachMissingSource() {
        when(profiles.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)));

        when(profiles.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(jobs.findJobAggregate(JOB_ID)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)));

        when(jobs.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)));
    }

    @Test
    void rejectsResumeLinkedToAnotherProfileOrJobBeforeRendering() {
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(new Resume(
                RESUME_ID, UUID.randomUUID(), JOB_ID, Resume.FORMAT_GERMANY, NOW, NOW, NOW, NOW
        )));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
        org.mockito.Mockito.verifyNoInteractions(documents, persistence);
    }

    @Test
    void rejectsResumeLinkedToAnotherJob() {
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(new Resume(
                RESUME_ID, PROFILE_ID, UUID.randomUUID(), Resume.FORMAT_GERMANY, NOW, NOW, NOW, NOW
        )));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
    }

    @Test
    void cleansStoredPdfWhenPersistenceFails() {
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(new Resume(
                RESUME_ID, PROFILE_ID, JOB_ID, "germany", NOW, NOW, NOW, NOW
        )));
        RuntimeException failure = new RuntimeException("database unavailable");
        doThrow(failure).when(persistence).replace(any(), any());

        assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
        verify(cleanup).enqueueAfterCompletion(any(UUID.class), anyString());
    }

    @Test
    void rejectsNonGermanyResumeBeforeRendering() {
        Resume nonGermany = mock(Resume.class);
        when(nonGermany.profileId()).thenReturn(PROFILE_ID);
        when(nonGermany.jobId()).thenReturn(JOB_ID);
        when(nonGermany.format()).thenReturn("canada");
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(nonGermany));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
        org.mockito.Mockito.verifyNoInteractions(documents, persistence);
    }

    @Test
    void rejectsStaleResumeBeforeRendering() {
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(new Resume(
                RESUME_ID, PROFILE_ID, JOB_ID, Resume.FORMAT_GERMANY,
                NOW.minusSeconds(1), NOW, NOW, NOW
        )));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
        org.mockito.Mockito.verifyNoInteractions(documents, persistence);
    }

    @Test
    void rejectsResumeWithStaleJobRevision() {
        when(resumes.findById(RESUME_ID)).thenReturn(Optional.of(new Resume(
                RESUME_ID, PROFILE_ID, JOB_ID, Resume.FORMAT_GERMANY,
                NOW, NOW.minusSeconds(1), NOW, NOW
        )));

        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));
    }

    @Test
    void preservesCleanupFailureWhenDocumentStorageFails() {
        RuntimeException storageFailure = new RuntimeException("storage unavailable");
        RuntimeException cleanupFailure = new RuntimeException("cleanup unavailable");
        when(documents.storeGeneratedDocumentFile(any(Path.class), anyString())).thenThrow(storageFailure);
        doThrow(cleanupFailure).when(cleanup).enqueueAfterCompletion(anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));

        assertSame(storageFailure, thrown);
        assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void cleansGeneratedFileWhenDocumentStorageFails() {
        RuntimeException storageFailure = new RuntimeException("storage unavailable");
        when(documents.storeGeneratedDocumentFile(any(Path.class), anyString())).thenThrow(storageFailure);

        assertSame(storageFailure, assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        )));

        verify(cleanup).enqueueAfterCompletion(anyString());
    }

    @Test
    void preservesCleanupFailureWhenPersistenceFails() {
        RuntimeException persistenceFailure = new RuntimeException("persistence unavailable");
        RuntimeException cleanupFailure = new RuntimeException("cleanup unavailable");
        doThrow(persistenceFailure).when(persistence).replace(any(), any());
        doThrow(cleanupFailure).when(cleanup).enqueueAfterCompletion(any(UUID.class), anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, RESUME_ID)
        ));

        assertSame(persistenceFailure, thrown);
        assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void validatesRequestIdsAndFilenameHelpers() {
        assertThrows(NullPointerException.class, () -> service.generate(null));
        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(null, JOB_ID, RESUME_ID)
        ));
        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, null, RESUME_ID)
        ));
        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class, () -> service.generate(
                new GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest(PROFILE_ID, JOB_ID, null)
        ));
        assertTrue(GenerateGermanCoverLetterService.buildFileName("Synthetic Candidate", PROFILE_ID).startsWith("germany_cover_letter_synthetic-candidate_aaaaaaaa_"));
        assertEquals("candidate", GenerateGermanCoverLetterService.slugify("   "));
        assertEquals("candidate", GenerateGermanCoverLetterService.slugify(null));
        assertEquals(40, GenerateGermanCoverLetterService.slugify("a".repeat(60)).length());
    }

    private static Resume resume(String format) {
        return new Resume(RESUME_ID, PROFILE_ID, JOB_ID, format, NOW, NOW, NOW, NOW);
    }

    private static ProfileAggregate profile() {
        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Candidate", "candidate@example.test",
                        "Backend delivery and reliable software systems", null, NOW, NOW),
                List.of(), List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW)),
                List.of(), List.of(),
                List.of(new Experience(UUID.randomUUID(), PROFILE_ID, "Java Developer", "Example Systems", "Berlin",
                        LocalDate.of(2024, 1, 1), null, "Built services.", 0, NOW)),
                List.of()
        );
    }

    private static JobAggregate job() {
        JobPosting posting = new JobPosting(JOB_ID, "text", "Synthetic source", "Backend Engineer", "Example GmbH", "Berlin",
                "Build Java services", null, null, "mid-level", null, "synthetic-cover-letter-job", NOW, NOW);
        return new JobAggregate(posting, List.of(), null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "Synthetic source", "synthetic-cover-letter-hash", NOW));
    }
}

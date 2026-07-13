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
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateGermanTailoredResumeServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @TempDir Path tempDir;

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
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactionLifecycle).afterRollback(org.mockito.ArgumentMatchers.any(Runnable.class));
        service = new GenerateGermanTailoredResumeService(
                profileRepository, jobRepository, personalDetailsRepository, resumeRepository,
                documentStorageService, documentRepository, fileRepository, cleanupService,
                transactionLifecycle, new OfflineGermanResumeTranslator(), tempDir,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void generatesRichBilingualResumeAndReplacesPreviousDocuments() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.of(
                new ProfilePersonalDetails(PROFILE_ID, LocalDate.of(1998, 1, 15), "Canadian", null, NOW, NOW)
        ));
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation -> {
            UUID documentId = UUID.randomUUID();
            return new StoredDocumentMetadata(documentId, "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 100L, "sha-" + documentId, NOW, NOW);
        });
        when(resumeRepository.replaceGermanyResume(org.mockito.ArgumentMatchers.any(ResumeRepository.ResumeAggregateWrite.class)))
                .thenAnswer(invocation -> {
                    ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
                    return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of());
                });
        assertEquals(false, service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)).replacedExisting());

        when(resumeRepository.replaceGermanyResume(org.mockito.ArgumentMatchers.any(ResumeRepository.ResumeAggregateWrite.class)))
                .thenAnswer(invocation -> {
                    ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
                    ResumeVariant previous = new ResumeVariant(UUID.randomUUID(), write.resume().id(), "en", UUID.randomUUID(), tempDir.resolve("old.pdf").toString(), NOW, NOW);
                    return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of(previous));
                });
        var result = service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID));
        assertEquals(2, result.variants().size());
        assertTrue(result.replacedExisting());
        verify(documentRepository).deleteFileIfUnreferenced(any());
        verify(cleanupService).enqueueAfterCommit(any());
    }

    @Test
    void generatesSparseProfileWithoutOptionalSections() {
        ProfileAggregate sparse = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Sparse Name", "sparse@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new Education(UUID.randomUUID(), PROFILE_ID, "Uni", "B.A.", "CS", "City",
                        LocalDate.of(2019, 1, 1), LocalDate.of(2023, 1, 1), null, NOW)),
                List.of(), List.of(), List.of()
        );
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(sparse));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.of(
                new ProfilePersonalDetails(PROFILE_ID, null, "  ", null, NOW, NOW)
        ));
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation ->
                new StoredDocumentMetadata(UUID.randomUUID(), "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 10L, "sha", NOW, NOW));
        when(resumeRepository.replaceGermanyResume(org.mockito.ArgumentMatchers.any(ResumeRepository.ResumeAggregateWrite.class)))
                .thenAnswer(invocation -> {
                    ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
                    return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of());
                });
        assertEquals(2, service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)).variants().size());
    }

    @Test
    void cleansUpWhenPersistenceFailsAfterPdfGeneration() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation ->
                new StoredDocumentMetadata(UUID.randomUUID(), "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 10L, "sha", NOW, NOW));
        when(resumeRepository.replaceGermanyResume(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(RuntimeException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)));
        verify(fileRepository, org.mockito.Mockito.atLeastOnce()).deleteIfExists(any());
    }

    @Test
    void cleansUpWhenDocumentStorageFailsDuringPdfGeneration() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenThrow(new RuntimeException("store failed"));
        assertThrows(RuntimeException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)));
        verify(fileRepository, org.mockito.Mockito.atLeastOnce()).deleteIfExists(any());
    }

    @Test
    void cleansUpWhenSecondLanguagePdfStorageFails() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any()))
                .thenReturn(new StoredDocumentMetadata(UUID.randomUUID(), "en.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 10L, "sha1", NOW, NOW))
                .thenThrow(new RuntimeException("de store failed"));
        AtomicInteger deletes = new AtomicInteger();
        doAnswer(invocation -> {
            if (deletes.incrementAndGet() >= 3) {
                throw new RuntimeException("cleanup failed");
            }
            return null;
        }).when(fileRepository).deleteIfExists(anyString());
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)));
        assertTrue(thrown.getMessage() != null);
        verify(fileRepository, org.mockito.Mockito.atLeast(2)).deleteIfExists(any());
    }

    @Test
    void throwsWhenProfileOrJobMissing() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.empty());
        assertThrows(ProfileNotFoundException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)));
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)));
    }

    @Test
    void buildsFilenameWithFormatCandidateNumberAndLanguageAbbr() {
        UUID profileId = UUID.fromString("dfdb2806-b130-420a-814b-dac650c3c439");
        String en = GenerateGermanTailoredResumeService.buildFileName("Joni Hysaj", profileId, "en");
        String de = GenerateGermanTailoredResumeService.buildFileName("Joni Hysaj", profileId, "de");
        assertTrue(en.startsWith("germany_joni-hysaj_dfdb2806_en_"));
        assertTrue(en.endsWith(".pdf"));
        assertTrue(de.startsWith("germany_joni-hysaj_dfdb2806_de_"));
        assertEquals("candidate", GenerateGermanTailoredResumeService.slugify("   "));
        assertEquals("candidate", GenerateGermanTailoredResumeService.slugify(null));
        String longSlug = GenerateGermanTailoredResumeService.slugify("A".repeat(80));
        assertTrue(longSlug.length() <= 40);
        assertTrue(GenerateGermanTailoredResumeService.buildFileName(null, profileId, "en").startsWith("germany_candidate_"));
    }

    @Test
    void generatesExperienceOnlyWithoutEducationSection() {
        ProfileAggregate experienceOnly = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Sparse Name", "sparse@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Experience(UUID.randomUUID(), PROFILE_ID, "Co", "Dev", null,
                        LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), "Built systems.", 0, NOW)),
                List.of(), List.of()
        );
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(experienceOnly));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation ->
                new StoredDocumentMetadata(UUID.randomUUID(), "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 10L, "sha", NOW, NOW));
        when(resumeRepository.replaceGermanyResume(org.mockito.ArgumentMatchers.any(ResumeRepository.ResumeAggregateWrite.class)))
                .thenAnswer(invocation -> {
                    ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
                    return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of());
                });
        assertEquals(2, service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID)).variants().size());
    }

    @Test
    void generatesWithProjectsWhenRequested() {
        when(profileRepository.findProfileAggregate(PROFILE_ID)).thenReturn(Optional.of(richProfile()));
        when(jobRepository.findJobAggregate(JOB_ID)).thenReturn(Optional.of(job()));
        when(personalDetailsRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(documentStorageService.storeGeneratedDocumentFile(any(), any())).thenAnswer(invocation ->
                new StoredDocumentMetadata(UUID.randomUUID(), "resume.pdf", DocumentStorageService.PDF_MEDIA_TYPE, 10L, "sha", NOW, NOW));
        when(resumeRepository.replaceGermanyResume(org.mockito.ArgumentMatchers.any(ResumeRepository.ResumeAggregateWrite.class)))
                .thenAnswer(invocation -> {
                    ResumeRepository.ResumeAggregateWrite write = invocation.getArgument(0);
                    return new ReplaceResult(write.resume(), write.variants().stream().map(ResumeRepository.VariantWrite::variant).toList(), List.of());
                });
        var result = service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, JOB_ID, true));
        assertEquals(2, result.variants().size());
        assertTrue(result.variants().getFirst().generatedFile().fileName().startsWith("germany_joni-hysaj_"));
    }

    @Test
    void rejectsNullIds() {
        assertThrows(ApplicationException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(null, JOB_ID)));
        assertThrows(ApplicationException.class, () -> service.generate(new GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest(PROFILE_ID, null)));
    }

    private static ProfileAggregate richProfile() {
        UUID projectId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID project2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Joni Hysaj", "joni@example.test", "Summary ignored", null, NOW, NOW),
                List.of(
                        new ProfileContact(UUID.randomUUID(), PROFILE_ID, "email", "joni@example.test", "Email", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), PROFILE_ID, "address", "Montreal, QC", "Address", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), PROFILE_ID, "phone", "+1-555-0100", null, NOW, NOW)
                ),
                List.of(
                        new ProfileLink(UUID.randomUUID(), PROFILE_ID, "github", "https://github.com/example", "  ", NOW, NOW),
                        new ProfileLink(UUID.randomUUID(), PROFILE_ID, "portfolio", "https://example.test", null, NOW, NOW)
                ),
                List.of(
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Python", "python", "Backend", 1, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Docker", "docker", null, 2, NOW)
                ),
                List.of(new ProfileLanguage(UUID.randomUUID(), PROFILE_ID, "English", "english", "fluent", 0, NOW)),
                List.of(new Education(UUID.randomUUID(), PROFILE_ID, "AUBG", "B.A. Computer Science", null, null, LocalDate.of(2019, 9, 1), LocalDate.of(2023, 5, 1), null, NOW)),
                List.of(
                        new Experience(UUID.randomUUID(), PROFILE_ID, "BKT", "Java Application Developer", "Tirana", LocalDate.of(2023, 11, 1), LocalDate.of(2024, 11, 1), "Automated financial processes using Spring Boot. Implemented JWT-based authentication. Supported deployment.", 0, NOW),
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Lines Co", "Dev", "City", LocalDate.of(2021, 1, 1), LocalDate.of(2021, 6, 1), "* bullet one\n- bullet two\n• bullet three", 4, NOW),
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Current Co", "Engineer", "Montreal", LocalDate.of(2025, 1, 1), null, "Ongoing product ownership.", 1, NOW),
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Past Co", "Dev", null, null, LocalDate.of(2022, 1, 1), "Older role.", 2, NOW),
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Undated Co", "Temp", null, null, null, "No dates.", 3, NOW)
                ),
                List.of(
                        new ProfileProject(projectId, PROFILE_ID, "Portfolio", "https://example.test", "Built a portfolio website with Next.js and TypeScript.", List.of(new ProjectTechnology(UUID.randomUUID(), projectId, "Next.js", "next.js", 0, NOW)), 0, NOW),
                        new ProfileProject(project2, PROFILE_ID, "Python ETL", null, "Python data transformation pipelines for analytics.", List.of(new ProjectTechnology(UUID.randomUUID(), project2, "Python", "python", 0, NOW)), 1, NOW)
                ),
                List.of()
        );
    }

    private static JobAggregate job() {
        JobPosting posting = new JobPosting(JOB_ID, "text", "EY", "Digitalization", "EY", "Stuttgart", "Python data transformation audit analytics", "CS degree", "Full-time", "Graduate", null, "fp-1", NOW, NOW);
        return new JobAggregate(posting, List.of(new JobSkill(UUID.randomUUID(), JOB_ID, "Python", "python", true, 0, NOW)), null, new JobTextIngestion(UUID.randomUUID(), JOB_ID, "label", "hash-1", NOW));
    }
}

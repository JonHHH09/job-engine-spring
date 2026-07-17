package org.instruct.jobenginespring.adapter.out.postgres.coverletter;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.adapter.out.postgres.resume.PostgresResumeRepository;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.CoverLetterAggregateWrite;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.VariantWrite;
import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.instruct.jobenginespring.application.document.GermanResumePersistenceService;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ResumeAggregateWrite;
import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterParagraph;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Testcontainers
class PostgresCoverLetterRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID RESUME_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID DOCUMENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine").withUsername("test").withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private PostgresCoverLetterRepository coverLetters;
    private PostgresResumeRepository resumes;
    private PostgresDocumentRepository documents;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema", "match", "resume", "cover_letter")
                .load().migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE cover_letter.cover_letters, resume.resumes, profile.profiles, "
                + "job_schema.jobs, document.documents, document.blobs CASCADE");
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        coverLetters = new PostgresCoverLetterRepository(named);
        resumes = new PostgresResumeRepository(named);
        documents = new PostgresDocumentRepository(named);
        seedSources(named);
    }

    @Test
    void migrationCreatesMasterVariantAndParagraphTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'cover_letter' ORDER BY table_name
                """, String.class);

        assertEquals(List.of("cover_letter_paragraphs", "cover_letter_variants", "cover_letters"), tables);
        assertTrue(coverLetters.findById(UUID.randomUUID()).isEmpty());
        assertTrue(resumes.findById(UUID.randomUUID()).isEmpty());
        assertEquals(RESUME_ID, resumes.findById(RESUME_ID).orElseThrow().id());
    }

    @Test
    void atomicallyReplacesStructuredGermanVariantAndProtectsReferencedDocument() {
        CoverLetterAggregateWrite first = write(DOCUMENT_ID, "first.pdf", "Erster Absatz");
        var firstResult = coverLetters.replace(first);

        assertTrue(firstResult.previousVariants().isEmpty());
        assertEquals(1, coverLetters.findVariants(first.coverLetter().id()).size());
        assertFalse(documents.deleteFileIfUnreferenced(DOCUMENT_ID));

        UUID replacementDocumentId = UUID.randomUUID();
        storeDocument(replacementDocumentId, "second");
        CoverLetterAggregateWrite second = write(replacementDocumentId, "second.pdf", "Zweiter Absatz");
        var secondResult = coverLetters.replace(second);

        assertEquals(List.of(first.variant().variant()), secondResult.previousVariants());
        assertEquals(second.coverLetter(), coverLetters.findById(second.coverLetter().id()).orElseThrow());
        assertEquals(List.of("Zweiter Absatz"), jdbc.queryForList(
                "SELECT text FROM cover_letter.cover_letter_paragraphs ORDER BY display_order", String.class
        ));
        assertTrue(documents.deleteFileIfUnreferenced(DOCUMENT_ID));

        var noParagraphs = coverLetters.replace(new CoverLetterAggregateWrite(
                second.coverLetter(), new VariantWrite(second.variant().variant(), List.of())
        ));
        assertEquals(List.of(second.variant().variant()), noParagraphs.previousVariants());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cover_letter.cover_letter_paragraphs", Integer.class));
    }

    @Test
    void rejectsAStaleResumeRevisionInsideTheLockedReplacementTransaction() {
        CoverLetterAggregateWrite stale = write(DOCUMENT_ID, "stale.pdf", "Absatz");
        CoverLetter parent = stale.coverLetter();
        CoverLetter staleParent = new CoverLetter(
                parent.id(), parent.profileId(), parent.jobId(), parent.resumeId(),
                parent.profileRevision(), parent.jobRevision(), NOW.minusSeconds(1), parent.createdAt(), parent.updatedAt()
        );

        assertThrows(ApplicationException.class, () -> coverLetters.replace(
                new CoverLetterAggregateWrite(staleParent, stale.variant())
        ));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cover_letter.cover_letters", Integer.class));
    }

    @Test
    void resumeReplacementDeletesLinkedCoverLetterBeforeTheResumeCascadeAndQueuesCleanup() {
        coverLetters.replace(write(DOCUMENT_ID, "linked.pdf", "Absatz"));
        GeneratedResumeCleanupService cleanup = mock(GeneratedResumeCleanupService.class);
        TransactionLifecycle transactions = mock(TransactionLifecycle.class);
        GermanResumePersistenceService persistence = new GermanResumePersistenceService(
                resumes, coverLetters, documents, cleanup, transactions
        );
        Resume replacement = new Resume(
                UUID.randomUUID(), PROFILE_ID, JOB_ID, Resume.FORMAT_GERMANY, NOW, NOW, NOW.plusSeconds(1), NOW.plusSeconds(1)
        );
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(ignored -> persistence.replace(
                new ResumeAggregateWrite(replacement, List.of()), List.of()
        ));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cover_letter.cover_letters", Integer.class));
        assertEquals(replacement.id(), resumes.findByProfileJobFormat(PROFILE_ID, JOB_ID, Resume.FORMAT_GERMANY).orElseThrow().id());
        verify(cleanup).enqueueAfterCommit(DOCUMENT_ID, "linked.pdf");
    }

    @Test
    void sourceDeletionRequiresCapturingCoverLetterAssetsBeforeForeignKeyCascade() {
        CoverLetterAggregateWrite write = write(DOCUMENT_ID, "source-deleted.pdf", "Absatz");
        coverLetters.replace(write);

        assertEquals(List.of(write.variant().variant()), coverLetters.lockAndFindAllByProfileId(PROFILE_ID));
        assertEquals(List.of(write.variant().variant()), coverLetters.lockAndFindAllByJobId(JOB_ID));

        jdbc.update("DELETE FROM profile.profiles WHERE id = ?", PROFILE_ID);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cover_letter.cover_letter_variants", Integer.class));
        assertTrue(documents.deleteFileIfUnreferenced(DOCUMENT_ID));
    }

    @Test
    void sourceDeletionLocksExistOnlyForTheCurrentProfileAndJob() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        PostgresProfileRepository profiles = new PostgresProfileRepository(named);
        PostgresJobRepository jobs = new PostgresJobRepository(named);

        assertTrue(profiles.lockProfileForDeletion(PROFILE_ID));
        assertFalse(profiles.lockProfileForDeletion(UUID.randomUUID()));
        assertTrue(jobs.lockJobForDeletion(JOB_ID));
        assertFalse(jobs.lockJobForDeletion(UUID.randomUUID()));
    }

    private CoverLetterAggregateWrite write(UUID documentId, String filePath, String paragraphText) {
        UUID coverLetterId = UUID.randomUUID();
        CoverLetter parent = new CoverLetter(
                coverLetterId, PROFILE_ID, JOB_ID, RESUME_ID, NOW, NOW, NOW, NOW, NOW
        );
        CoverLetterVariant variant = new CoverLetterVariant(
                UUID.randomUUID(), coverLetterId, "germany", "de", documentId, filePath,
                "Bewerbung als Backend Engineer", "Sehr geehrte Damen und Herren,",
                "Mit freundlichen Grüßen", "Synthetic Candidate", NOW, NOW
        );
        CoverLetterParagraph paragraph = new CoverLetterParagraph(UUID.randomUUID(), variant.id(), 0, paragraphText);
        return new CoverLetterAggregateWrite(parent, new VariantWrite(variant, List.of(paragraph)));
    }

    private void seedSources(NamedParameterJdbcTemplate named) {
        PostgresProfileRepository profiles = new PostgresProfileRepository(named);
        PostgresJobRepository jobs = new PostgresJobRepository(named);
        profiles.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Candidate", "candidate@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        jobs.saveJobAggregate(new JobAggregate(
                new JobPosting(JOB_ID, "text", "Synthetic source", "Backend Engineer", "Example GmbH",
                        "Berlin", "Build reliable services", null, null, null, null, "cover-letter-fingerprint", NOW, NOW),
                List.of(), null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "Synthetic source", "cover-letter-hash", NOW)
        ));
        resumes.replaceGermanyResume(new ResumeAggregateWrite(
                new Resume(RESUME_ID, PROFILE_ID, JOB_ID, Resume.FORMAT_GERMANY, NOW, NOW, NOW, NOW), List.of()
        ));
        storeDocument(DOCUMENT_ID, "first");
    }

    private void storeDocument(UUID id, String suffix) {
        byte[] content = ("%PDF-1.4 synthetic " + suffix).getBytes(StandardCharsets.UTF_8);
        String sha = String.format("%064x", Math.abs((long) suffix.hashCode()) + 1L);
        documents.saveFile(new StoredDocumentFile(
                id, "cover-letter-" + suffix + ".pdf", "application/pdf", content.length,
                sha, content, NOW, NOW
        ));
    }
}

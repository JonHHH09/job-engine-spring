package org.instruct.jobenginespring.application.document;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.filesystem.document.LocalGeneratedResumeFileRepository;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresGeneratedResumeCleanupRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileResumeDocumentRepository;
import org.instruct.jobenginespring.adapter.out.transaction.SpringTransactionLifecycle;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers
class GeneratedResumeAssetServiceIntegrationTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private PostgresProfileRepository profileRepository;
    private GeneratePdfResumeService generationService;
    private GeneratedResumeAssetService assetService;
    private TransactionTemplate transactions;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles, document.documents, document.blobs, "
                + "document.generated_resume_file_cleanups CASCADE");
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        profileRepository = new PostgresProfileRepository(namedJdbc);
        PostgresDocumentRepository documentRepository = new PostgresDocumentRepository(namedJdbc);
        PostgresProfileResumeDocumentRepository resumeDocumentRepository =
                new PostgresProfileResumeDocumentRepository(JdbcClient.create(namedJdbc));
        LocalGeneratedResumeFileRepository fileRepository = new LocalGeneratedResumeFileRepository(tempDir);
        SpringTransactionLifecycle transactionLifecycle = new SpringTransactionLifecycle();
        GeneratedResumeCleanupService cleanupService = new GeneratedResumeCleanupService(
                new PostgresGeneratedResumeCleanupRepository(JdbcClient.create(namedJdbc)),
                documentRepository,
                fileRepository,
                transactionLifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        assetService = new GeneratedResumeAssetService(
                profileRepository,
                resumeDocumentRepository,
                documentRepository,
                transactionLifecycle,
                cleanupService
        );
        DocumentStorageService storageService = new DocumentStorageService(
                documentRepository,
                mock(PdfTextExtractionService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ProfileResumePdfGenerationWorkflow workflow = new ProfileResumePdfGenerationWorkflow(
                profileRepository,
                storageService,
                assetService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        generationService = new GeneratePdfResumeService(workflow, tempDir);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        profileRepository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Test Profile", "profile@example.test", "Summary", null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
    }

    @Test
    void regenerationAndProfileDeletionLeaveNoPrivateDocumentBlobOrFileOrphans() {
        GeneratePdfResumeService.GeneratePdfResumeResult first = transactions.execute(
                status -> generationService.generatePdfResume(new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID))
        );
        GeneratePdfResumeService.GeneratePdfResumeResult second = transactions.execute(
                status -> generationService.generatePdfResume(new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID))
        );

        assertNotEquals(first.documentId(), second.documentId());
        assertFalse(Files.exists(Path.of(first.filePath())));
        assertTrue(Files.exists(Path.of(second.filePath())));
        assertEquals(1, count("profile.profile_resume_documents"));
        assertEquals(1, count("document.documents"));
        assertEquals(1, count("document.blobs"));

        assertTrue(Boolean.TRUE.equals(transactions.execute(status -> assetService.deleteProfile(PROFILE_ID))));

        assertFalse(Files.exists(Path.of(second.filePath())));
        assertEquals(0, count("profile.profile_resume_documents"));
        assertEquals(0, count("document.documents"));
        assertEquals(0, count("document.blobs"));
    }

    @Test
    void rollbackRemovesNewDatabaseRowsAndGeneratedFile() {
        AtomicReference<String> generatedPath = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            var generated = generationService.generatePdfResume(
                    new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
            );
            generatedPath.set(generated.filePath());
            throw new IllegalStateException("force persistence rollback");
        }));

        assertFalse(Files.exists(Path.of(generatedPath.get())));
        assertEquals(0, count("profile.profile_resume_documents"));
        assertEquals(0, count("document.documents"));
        assertEquals(0, count("document.blobs"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM document.generated_resume_file_cleanups WHERE status = 'COMPLETED'",
                Integer.class
        ));
        assertEquals(1, count("profile.profiles"));
    }

    @Test
    void ambiguousPostCommitFailurePreservesReferencedPdfAndCompletesCompensation() {
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        PostgresDocumentRepository documentRepository = new PostgresDocumentRepository(namedJdbc);
        var resumeDocuments = new PostgresProfileResumeDocumentRepository(JdbcClient.create(namedJdbc));
        var fileRepository = new LocalGeneratedResumeFileRepository(tempDir);
        var transactionLifecycle = new SpringTransactionLifecycle();
        var cleanupService = new GeneratedResumeCleanupService(
                new PostgresGeneratedResumeCleanupRepository(JdbcClient.create(namedJdbc)),
                documentRepository,
                fileRepository,
                transactionLifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var committedAssets = new GeneratedResumeAssetService(
                profileRepository,
                resumeDocuments,
                documentRepository,
                transactionLifecycle,
                cleanupService
        );
        var ambiguousAssets = new GeneratedResumeAssetService(
                profileRepository,
                resumeDocuments,
                documentRepository,
                transactionLifecycle,
                cleanupService
        ) {
            @Override
            public ProfileResumeDocumentRepository.Replacement replace(
                    org.instruct.jobenginespring.domain.profile.ProfileResumeDocument resumeDocument,
                    String generatedFilePath
            ) {
                transactions.execute(status -> committedAssets.replace(resumeDocument, generatedFilePath));
                throw new IllegalStateException("commit acknowledgement lost");
            }
        };
        var storageService = new DocumentStorageService(
                documentRepository, mock(PdfTextExtractionService.class), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var ambiguousGeneration = new GeneratePdfResumeService(
                new ProfileResumePdfGenerationWorkflow(
                        profileRepository, storageService, ambiguousAssets, Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                tempDir
        );

        assertThrows(IllegalStateException.class, () -> ambiguousGeneration.generatePdfResume(
                new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
        ));

        String referencedPath = jdbc.queryForObject(
                "SELECT file_path FROM profile.profile_resume_documents WHERE profile_id = ?",
                String.class,
                PROFILE_ID
        );
        assertTrue(Files.exists(Path.of(referencedPath)));
        assertEquals(1, count("profile.profile_resume_documents"));
        assertEquals(1, count("document.documents"));
        assertEquals(1, count("document.blobs"));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM document.generated_resume_file_cleanups WHERE file_path = ?",
                String.class,
                referencedPath
        ));
    }

    @Test
    void concurrentRegenerationSerializesReplacementCleanup() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<GeneratePdfResumeService.GeneratePdfResumeResult> first = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> generationService.generatePdfResume(
                        new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
                ));
            });
            Future<GeneratePdfResumeService.GeneratePdfResumeResult> second = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> generationService.generatePdfResume(
                        new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
                ));
            });
            start.countDown();

            var firstResult = first.get();
            var secondResult = second.get();

            assertEquals(1, count("profile.profile_resume_documents"));
            assertEquals(1, count("document.documents"));
            assertEquals(1, count("document.blobs"));
            assertEquals(1, List.of(firstResult.filePath(), secondResult.filePath()).stream()
                    .filter(path -> Files.exists(Path.of(path)))
                    .count());
        }
    }

    @Test
    void committedRegenerationReportsSuccessWhileFailedFileCleanupRemainsDurableAndRetryable() {
        var first = transactions.execute(status -> generationService.generatePdfResume(
                new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
        ));
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        PostgresDocumentRepository documentRepository = new PostgresDocumentRepository(namedJdbc);
        LocalGeneratedResumeFileRepository localFiles = new LocalGeneratedResumeFileRepository(tempDir);
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        GeneratedResumeFileRepository flakyFiles = filePath -> {
            if (filePath.equals(first.filePath()) && failFirstDelete.getAndSet(false)) {
                throw new IllegalStateException("simulated filesystem outage");
            }
            localFiles.deleteIfExists(filePath);
        };
        SpringTransactionLifecycle transactionLifecycle = new SpringTransactionLifecycle();
        GeneratedResumeCleanupService cleanupService = new GeneratedResumeCleanupService(
                new PostgresGeneratedResumeCleanupRepository(JdbcClient.create(namedJdbc)),
                documentRepository,
                flakyFiles,
                transactionLifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        GeneratedResumeAssetService flakyAssetService = new GeneratedResumeAssetService(
                profileRepository,
                new PostgresProfileResumeDocumentRepository(JdbcClient.create(namedJdbc)),
                documentRepository,
                transactionLifecycle,
                cleanupService
        );
        DocumentStorageService storageService = new DocumentStorageService(
                documentRepository, mock(PdfTextExtractionService.class), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var flakyGenerationService = new GeneratePdfResumeService(
                new ProfileResumePdfGenerationWorkflow(
                        profileRepository, storageService, flakyAssetService, Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                tempDir
        );

        var committed = transactions.execute(status -> flakyGenerationService.generatePdfResume(
                new GeneratePdfResumeService.GeneratePdfResumeRequest(PROFILE_ID)
        ));

        assertTrue(Files.exists(Path.of(first.filePath())));
        assertTrue(Files.exists(Path.of(committed.filePath())));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM document.generated_resume_file_cleanups WHERE file_path = ?",
                String.class,
                first.filePath()
        ));
        jdbc.update(
                "UPDATE document.generated_resume_file_cleanups SET next_attempt_at = ? WHERE file_path = ?",
                java.sql.Timestamp.from(NOW),
                first.filePath()
        );

        cleanupService.retryDueTasks();

        assertFalse(Files.exists(Path.of(first.filePath())));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM document.generated_resume_file_cleanups WHERE file_path = ?",
                String.class,
                first.filePath()
        ));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}

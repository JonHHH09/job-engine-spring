package org.instruct.jobenginespring.application.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfilePdfSourceRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.GeneratedResumeAssetService;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ProfilePdfIngestionConcurrencyIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-14T16:00:00Z");
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EXTRACTION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String SHA256 = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private PostgresProfileRepository profiles;
    private PostgresProfilePdfSourceRepository sources;
    private StoredPdfTextExtractionResult storedExtraction;

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
        jdbc.update("TRUNCATE TABLE profile.profiles, document.documents, document.blobs CASCADE");
        var namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        profiles = new PostgresProfileRepository(namedJdbc);
        sources = org.mockito.Mockito.spy(new PostgresProfilePdfSourceRepository(JdbcClient.create(namedJdbc)));
        var documents = new PostgresDocumentRepository(namedJdbc);
        byte[] content = "%PDF-concurrent".getBytes(StandardCharsets.US_ASCII);
        var stored = documents.saveFile(new StoredDocumentFile(
                DOCUMENT_ID, "resume.pdf", "application/pdf", content.length, SHA256, content, NOW, NOW
        ));
        documents.savePdfExtraction(new PdfExtractionRecord(
                EXTRACTION_ID, DOCUMENT_ID, "test", 4, 1, false, "text", NOW
        ));
        storedExtraction = new StoredPdfTextExtractionResult(
                stored, EXTRACTION_ID,
                new PdfTextExtractionResult("resume.pdf", 1, 4, false, "text", List.of())
        );
    }

    @Test
    void concurrentIngestionPersistsOneProfileAndReturnsTheSameSourceWinner() throws Exception {
        var precheckBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            precheckBarrier.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(sources).findByDocumentSha256(SHA256);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
            return invocation.callRealMethod();
        }).when(sources).insertOrFind(any());

        try (var context = applicationContext();
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var service = context.getBean(ProfilePdfIngestionPersistenceService.class);
            var first = executor.submit(() -> service.persist(request(), storedExtraction, writeRequest("first@example.test")));
            var second = executor.submit(() -> service.persist(request(), storedExtraction, writeRequest("second@example.test")));

            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);

            assertEquals(Set.of(IngestionStatus.CREATED_PROFILE, IngestionStatus.REUSED_EXISTING_SOURCE),
                    Set.of(firstResult.status(), secondResult.status()));
            assertEquals(firstResult.profileId(), secondResult.profileId());
            assertEquals(firstResult.sourceLinkId(), secondResult.sourceLinkId());
            assertEquals(1, count("profile.profiles"));
            assertEquals(1, count("profile.profile_pdf_sources"));
        }
    }

    private AnnotationConfigApplicationContext applicationContext() {
        var assetService = mock(GeneratedResumeAssetService.class);
        when(assetService.deleteProfile(any())).thenAnswer(invocation -> profiles.deleteProfile(invocation.getArgument(0)));
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(ProfileRepository.class, () -> profiles);
        context.registerBean(ProfilePdfSourceRepository.class, () -> sources);
        context.registerBean(GeneratedResumeAssetService.class, () -> assetService);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.register(ProfileService.class, ProfileIdentityMatcher.class, ProfilePdfIngestionPersistenceService.class);
        context.refresh();
        return context;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static IngestProfileFromStoredPdfRequest request() {
        return new IngestProfileFromStoredPdfRequest(DOCUMENT_ID, null, null, null);
    }

    private static ProfileService.ProfileWriteRequest writeRequest(String email) {
        return new ProfileService.ProfileWriteRequest(
                "Candidate", email, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}

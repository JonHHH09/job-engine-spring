package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresProfilePdfSourceRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-03T19:15:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FILE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EXTRACTION_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SOURCE_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    private PostgresProfileRepository profileRepository;
    private PostgresDocumentRepository documentRepository;
    private PostgresProfilePdfSourceRepository sourceRepository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles, document.documents, document.blobs CASCADE");
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        profileRepository = new PostgresProfileRepository(namedJdbc);
        documentRepository = new PostgresDocumentRepository(namedJdbc);
        sourceRepository = new PostgresProfilePdfSourceRepository(JdbcClient.create(namedJdbc));
    }

    @Test
    void savesAndFindsOneToOneProfilePdfSource() {
        seedProfileFileAndExtraction();
        ProfilePdfSource source = new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", NOW);

        ProfilePdfSource saved = sourceRepository.save(source);

        assertEquals(source, saved);
        assertEquals(source, sourceRepository.findByProfileId(PROFILE_ID).orElseThrow());
        assertEquals(source, sourceRepository.findByPdfExtractionId(EXTRACTION_ID).orElseThrow());
        assertEquals(source, sourceRepository.findByDocumentSha256(SHA256).orElseThrow());
    }

    @Test
    void enforcesOneProfilePerExtractionAndOneExtractionPerProfile() {
        seedProfileFileAndExtraction();
        sourceRepository.save(new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", NOW));

        assertThrows(DataIntegrityViolationException.class, () -> sourceRepository.save(new ProfilePdfSource(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                PROFILE_ID,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "resume_pdf",
                NOW
        )));
        assertThrows(DataIntegrityViolationException.class, () -> sourceRepository.save(new ProfilePdfSource(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                EXTRACTION_ID,
                "resume_pdf",
                NOW
        )));
    }

    @Test
    void deletingProfileCascadesSourceLinkButLinkedExtractionIsRestricted() {
        seedProfileFileAndExtraction();
        sourceRepository.save(new ProfilePdfSource(SOURCE_ID, PROFILE_ID, EXTRACTION_ID, "resume_pdf", NOW));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM document.pdf_extractions WHERE id = ?",
                EXTRACTION_ID
        ));
        assertTrue(profileRepository.deleteProfile(PROFILE_ID));
        assertFalse(sourceRepository.findByProfileId(PROFILE_ID).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document.pdf_extractions WHERE id = ?", Integer.class, EXTRACTION_ID));
    }

    private void seedProfileFileAndExtraction() {
        profileRepository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Agentic Dev", "agentic@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        documentRepository.saveFile(new StoredDocumentFile(
                FILE_ID,
                "resume.pdf",
                "application/pdf",
                16,
                SHA256,
                "%PDF-1.3 fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                NOW,
                NOW
        ));
        documentRepository.savePdfExtraction(new PdfExtractionRecord(
                EXTRACTION_ID,
                FILE_ID,
                "test-extractor",
                20,
                1,
                false,
                "Agentic Dev agentic@example.test",
                NOW
        ));
    }
}

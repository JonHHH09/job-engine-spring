package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
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
class PostgresProfileResumeDocumentRepositoryIntegrationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-07-05T16:30:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-05T17:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LINK_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FIRST_DOCUMENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SECOND_DOCUMENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    private PostgresProfileRepository profileRepository;
    private PostgresDocumentRepository documentRepository;
    private PostgresProfileResumeDocumentRepository resumeDocumentRepository;

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
        resumeDocumentRepository = new PostgresProfileResumeDocumentRepository(JdbcClient.create(namedJdbc));
    }

    @Test
    void savesAndFindsGeneratedResumeDocumentLink() {
        seedProfileAndDocuments();
        ProfileResumeDocument link = new ProfileResumeDocument(
                LINK_ID,
                PROFILE_ID,
                FIRST_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                "master_resume",
                CREATED_AT,
                CREATED_AT
        );

        ProfileResumeDocument saved = resumeDocumentRepository.save(link);

        assertEquals(link, saved);
        assertEquals(link, resumeDocumentRepository.findByProfileIdAndResumeType(PROFILE_ID, "master_resume").orElseThrow());
    }

    @Test
    void upsertsOneResumeDocumentLinkPerProfile() {
        seedProfileAndDocuments();
        ProfileResumeDocument first = new ProfileResumeDocument(
                LINK_ID,
                PROFILE_ID,
                FIRST_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                "master_resume",
                CREATED_AT,
                CREATED_AT
        );
        ProfileResumeDocument replacement = new ProfileResumeDocument(
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                PROFILE_ID,
                SECOND_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                "master_resume",
                UPDATED_AT,
                UPDATED_AT
        );

        resumeDocumentRepository.save(first);
        ProfileResumeDocument savedReplacement = resumeDocumentRepository.save(replacement);

        assertEquals(LINK_ID, savedReplacement.id());
        assertEquals(SECOND_DOCUMENT_ID, savedReplacement.documentId());
        assertEquals(CREATED_AT, savedReplacement.createdAt());
        assertEquals(UPDATED_AT, savedReplacement.updatedAt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM profile.profile_resume_documents", Integer.class));
    }

    @Test
    void keepsOneResumeDocumentLinkPerProfileAndResumeType() {
        seedProfileAndDocuments();
        ProfileResumeDocument master = new ProfileResumeDocument(
                LINK_ID,
                PROFILE_ID,
                FIRST_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                "master_resume",
                CREATED_AT,
                CREATED_AT
        );
        ProfileResumeDocument canadian = new ProfileResumeDocument(
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                PROFILE_ID,
                SECOND_DOCUMENT_ID,
                "tmp/generated-pdfs/canadian-resume/canadian-resume.pdf",
                "canadian_resume",
                UPDATED_AT,
                UPDATED_AT
        );

        resumeDocumentRepository.save(master);
        resumeDocumentRepository.save(canadian);

        assertEquals(master, resumeDocumentRepository.findByProfileIdAndResumeType(PROFILE_ID, "master_resume").orElseThrow());
        assertEquals(canadian, resumeDocumentRepository.findByProfileIdAndResumeType(PROFILE_ID, "canadian_resume").orElseThrow());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM profile.profile_resume_documents", Integer.class));
    }

    @Test
    void enforcesOneLinkedProfilePerGeneratedDocumentAndRestrictsDocumentDeletion() {
        seedProfileAndDocuments();
        ProfileResumeDocument link = new ProfileResumeDocument(
                LINK_ID,
                PROFILE_ID,
                FIRST_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/master-resume.pdf",
                "master_resume",
                CREATED_AT,
                CREATED_AT
        );
        resumeDocumentRepository.save(link);

        assertThrows(DataIntegrityViolationException.class, () -> resumeDocumentRepository.save(new ProfileResumeDocument(
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                FIRST_DOCUMENT_ID,
                "tmp/generated-pdfs/master-resume/other.pdf",
                "master_resume",
                CREATED_AT,
                CREATED_AT
        )));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM document.documents WHERE id = ?",
                FIRST_DOCUMENT_ID
        ));
        assertTrue(profileRepository.deleteProfile(PROFILE_ID));
        assertFalse(resumeDocumentRepository.findByProfileIdAndResumeType(PROFILE_ID, "master_resume").isPresent());
    }

    private void seedProfileAndDocuments() {
        profileRepository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Agentic Dev", "agentic@example.test", null, null, CREATED_AT, CREATED_AT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        documentRepository.saveFile(new StoredDocumentFile(
                FIRST_DOCUMENT_ID,
                "master-resume.pdf",
                "application/pdf",
                16,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "%PDF-1.3 first".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                CREATED_AT,
                CREATED_AT
        ));
        documentRepository.saveFile(new StoredDocumentFile(
                SECOND_DOCUMENT_ID,
                "master-resume.pdf",
                "application/pdf",
                17,
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "%PDF-1.3 second".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                UPDATED_AT,
                UPDATED_AT
        ));
    }
}

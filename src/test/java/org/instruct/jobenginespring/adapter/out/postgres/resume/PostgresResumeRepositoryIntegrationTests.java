package org.instruct.jobenginespring.adapter.out.postgres.resume;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfilePersonalDetailsRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.EntryWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ReplaceResult;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ResumeAggregateWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.SectionWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.VariantWrite;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresResumeRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-13T01:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOC_EN = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID DOC_DE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID DOC_EN2 = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID DOC_DE2 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine").withUsername("test").withPassword("test");

    private static JdbcTemplate jdbc;
    private PostgresResumeRepository resumeRepository;
    private PostgresProfilePersonalDetailsRepository personalDetailsRepository;
    private PostgresProfileRepository profileRepository;
    private PostgresJobRepository jobRepository;
    private PostgresDocumentRepository documentRepository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema", "match", "resume")
                .load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE resume.resumes, profile.profiles, job_schema.jobs, document.documents, document.blobs CASCADE");
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        profileRepository = new PostgresProfileRepository(named);
        jobRepository = new PostgresJobRepository(named);
        documentRepository = new PostgresDocumentRepository(named);
        resumeRepository = new PostgresResumeRepository(named);
        personalDetailsRepository = new PostgresProfilePersonalDetailsRepository(JdbcClient.create(named));
    }

    @Test
    void flywayCreatesResumeSchemaTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'resume' ORDER BY table_name
                """, String.class);
        assertEquals(List.of("resume_entries", "resume_entry_bullets", "resume_sections", "resume_variants", "resumes"), tables);
    }

    @Test
    void savesPersonalDetailsAndReplacesGermanyResumeWithStructuredContent() {
        seedProfileJobAndDocuments();
        ProfilePersonalDetails details = new ProfilePersonalDetails(PROFILE_ID, LocalDate.of(1998, 1, 15), "Canadian", null, NOW, NOW);
        assertEquals(details, personalDetailsRepository.save(details));
        assertEquals(details, personalDetailsRepository.findByProfileId(PROFILE_ID).orElseThrow());

        ProfilePersonalDetails updated = new ProfilePersonalDetails(PROFILE_ID, null, "Albanian", null, NOW, NOW.plusSeconds(10));
        ProfilePersonalDetails savedUpdate = personalDetailsRepository.save(updated);
        assertEquals(null, savedUpdate.dateOfBirth());
        assertEquals("Albanian", savedUpdate.nationality());
        assertEquals(savedUpdate, personalDetailsRepository.findByProfileId(PROFILE_ID).orElseThrow());

        Resume first = new Resume(UUID.randomUUID(), PROFILE_ID, JOB_ID, "germany", NOW, NOW, NOW, NOW);
        ReplaceResult firstReplace = resumeRepository.replaceGermanyResume(write(first, DOC_EN, DOC_DE, "first"));
        assertEquals(0, firstReplace.previousVariants().size());
        assertEquals(2, firstReplace.variants().size());
        assertTrue(resumeRepository.findByProfileJobFormat(PROFILE_ID, JOB_ID, "germany").isPresent());
        assertEquals(2, resumeRepository.findVariants(first.id()).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resume.resumes", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM resume.resume_variants", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM resume.resume_sections", Integer.class) >= 2);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM resume.resume_entries", Integer.class) >= 2);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM resume.resume_entry_bullets", Integer.class) >= 2);

        Resume second = new Resume(UUID.randomUUID(), PROFILE_ID, JOB_ID, "germany", NOW, NOW, NOW, NOW);
        ReplaceResult secondReplace = resumeRepository.replaceGermanyResume(write(second, DOC_EN2, DOC_DE2, "second"));
        assertEquals(2, secondReplace.previousVariants().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resume.resumes", Integer.class));
        assertEquals(second.id(), resumeRepository.findByProfileJobFormat(PROFILE_ID, JOB_ID, "germany").orElseThrow().id());
    }

    private ResumeAggregateWrite write(Resume resume, UUID enDoc, UUID deDoc, String label) {
        ResumeVariant en = new ResumeVariant(UUID.randomUUID(), resume.id(), "en", enDoc, "tmp/" + label + "-en.pdf", NOW, NOW);
        ResumeVariant de = new ResumeVariant(UUID.randomUUID(), resume.id(), "de", deDoc, "tmp/" + label + "-de.pdf", NOW, NOW);
        return new ResumeAggregateWrite(resume, List.of(variant(en, "Contact"), variant(de, "Kontakt")));
    }

    private VariantWrite variant(ResumeVariant variant, String personalTitle) {
        ResumeSection personal = new ResumeSection(UUID.randomUUID(), variant.id(), ResumeSection.PERSONAL, personalTitle, 0);
        ResumeEntry email = new ResumeEntry(UUID.randomUUID(), personal.id(), ResumeEntry.PERSONAL_FIELD, 0, "Email", null, null, null, null, "a@example.test");
        ResumeSection experience = new ResumeSection(UUID.randomUUID(), variant.id(), ResumeSection.EXPERIENCE, "Experience", 1);
        ResumeEntry role = new ResumeEntry(UUID.randomUUID(), experience.id(), ResumeEntry.EXPERIENCE, 0, "Engineer", "Acme", "Montreal", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 1, 1), null);
        ResumeEntryBullet bullet = new ResumeEntryBullet(UUID.randomUUID(), role.id(), 0, "Built systems");
        return new VariantWrite(variant, List.of(
                new SectionWrite(personal, List.of(new EntryWrite(email, List.of()))),
                new SectionWrite(experience, List.of(new EntryWrite(role, List.of(bullet))))
        ));
    }

    private void seedProfileJobAndDocuments() {
        profileRepository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Agentic Dev", "agentic@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        JobPosting posting = new JobPosting(JOB_ID, "text", "EY", "Digitalization", "EY", "Stuttgart", "Python data", null, null, null, null, "fp-resume-it", NOW, NOW);
        jobRepository.saveJobAggregate(new JobAggregate(posting, List.of(), null, new JobTextIngestion(UUID.randomUUID(), JOB_ID, "label", "hash-resume-it", NOW)));
        storeDoc(DOC_EN, "en1"); storeDoc(DOC_DE, "de1"); storeDoc(DOC_EN2, "en2"); storeDoc(DOC_DE2, "de2");
    }

    private void storeDoc(UUID id, String suffix) {
        String content = "%PDF-1.3 " + suffix;
        String sha = switch (suffix) {
            case "en1" -> "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            case "de1" -> "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            case "en2" -> "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
            default -> "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        };
        documentRepository.saveFile(new StoredDocumentFile(id, "resume-" + suffix + ".pdf", "application/pdf", content.length(), sha, content.getBytes(StandardCharsets.UTF_8), NOW, NOW));
    }
}

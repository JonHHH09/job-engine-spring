package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgresProfileRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-02T14:30:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTACT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SKILL_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PROJECT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID TECHNOLOGY_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;

    private PostgresProfileRepository repository;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile")
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
        jdbc.update("TRUNCATE TABLE profile.profiles CASCADE");
        repository = new PostgresProfileRepository(jdbc);
    }

    @Test
    void flywayCreatesProfileSchemaTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'profile'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        assertEquals(List.of(
                "education",
                "experiences",
                "profile_contacts",
                "profile_languages",
                "profile_links",
                "profile_skills",
                "profiles",
                "project_technologies",
                "projects"
        ), tables);
    }

    @Test
    void savesAndFindsProfileAggregateWithChildren() {
        repository.saveProfileAggregate(sampleAggregate(
                "Agentic Dev",
                "agentic-dev@example.test",
                "Builds MCP-native systems",
                List.of(new ProfileContact(CONTACT_ID, PROFILE_ID, "Location", "Montreal", "home", NOW, NOW)),
                List.of(new ProfileSkill(SKILL_ID, PROFILE_ID, "Spring AI", null, "backend", 1, NOW))
        ));

        Optional<ProfileAggregate> found = repository.findProfileAggregate(PROFILE_ID);

        assertTrue(found.isPresent());
        ProfileAggregate aggregate = found.orElseThrow();
        assertEquals("Agentic Dev", aggregate.profile().fullName());
        assertEquals("agentic-dev@example.test", aggregate.profile().email());
        assertEquals("location", aggregate.contacts().getFirst().contactType());
        assertEquals("Spring AI", aggregate.skills().getFirst().skill());
        assertEquals("spring ai", aggregate.skills().getFirst().normalizedSkill());
        assertEquals("Profile Repository", aggregate.projects().getFirst().name());
        assertEquals("PostgreSQL", aggregate.projects().getFirst().technologies().getFirst().technology());
    }

    @Test
    void saveReplacesProfileOwnedChildrenOnUpdate() {
        repository.saveProfileAggregate(sampleAggregate(
                "Agentic Dev",
                "agentic-dev@example.test",
                "Initial",
                List.of(new ProfileContact(CONTACT_ID, PROFILE_ID, "Location", "Remote", null, NOW, NOW)),
                List.of(new ProfileSkill(SKILL_ID, PROFILE_ID, "Java", null, "backend", 1, NOW))
        ));

        UUID replacementContactId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID replacementSkillId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ProfileAggregate updated = sampleAggregate(
                "Agentic Dev",
                "agentic-dev@example.test",
                "Updated",
                List.of(new ProfileContact(replacementContactId, PROFILE_ID, "Timezone", "UTC", null, NOW, NOW)),
                List.of(new ProfileSkill(replacementSkillId, PROFILE_ID, "PostgreSQL", null, "database", 2, NOW))
        );

        ProfileAggregate saved = repository.saveProfileAggregate(updated);

        assertEquals("Updated", saved.profile().summary());
        assertEquals(List.of("timezone"), saved.contacts().stream().map(ProfileContact::contactType).toList());
        assertEquals(List.of("postgresql"), saved.skills().stream().map(ProfileSkill::normalizedSkill).toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM profile.profile_contacts WHERE profile_id = ?", Integer.class, PROFILE_ID));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM profile.profile_skills WHERE profile_id = ?", Integer.class, PROFILE_ID));
    }

    @Test
    void deleteProfileCascadesChildren() {
        repository.saveProfileAggregate(sampleAggregate(
                "Agentic Dev",
                "agentic-dev@example.test",
                "Delete test",
                List.of(new ProfileContact(CONTACT_ID, PROFILE_ID, "Location", "Remote", null, NOW, NOW)),
                List.of(new ProfileSkill(SKILL_ID, PROFILE_ID, "Java", null, "backend", 1, NOW))
        ));

        assertTrue(repository.deleteProfile(PROFILE_ID));

        assertFalse(repository.findProfileAggregate(PROFILE_ID).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM profile.profile_contacts WHERE profile_id = ?", Integer.class, PROFILE_ID));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM profile.profile_skills WHERE profile_id = ?", Integer.class, PROFILE_ID));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                FROM profile.project_technologies technology
                JOIN profile.projects project ON project.id = technology.project_id
                WHERE project.profile_id = ?
                """, Integer.class, PROFILE_ID));
    }

    private static ProfileAggregate sampleAggregate(
            String fullName,
            String email,
            String summary,
            List<ProfileContact> contacts,
            List<ProfileSkill> skills
    ) {
        ProjectTechnology technology = new ProjectTechnology(
                TECHNOLOGY_ID,
                PROJECT_ID,
                "PostgreSQL",
                null,
                1,
                NOW
        );
        ProfileProject project = new ProfileProject(
                PROJECT_ID,
                PROFILE_ID,
                "Profile Repository",
                "https://example.test/profile-repository",
                "Sanitized integration-test fixture",
                List.of(technology),
                1,
                NOW
        );

        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, fullName, email, summary, null, NOW, NOW),
                contacts,
                null,
                skills,
                null,
                null,
                null,
                List.of(project),
                List.of(technology)
        );
    }
}

package org.instruct.jobenginespring.application.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Testcontainers
class PostgresProfileSearchIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-09T14:30:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static CountingDataSource dataSource;
    private static JdbcTemplate jdbc;

    private PostgresProfileRepository repository;
    private ProfileSearchService service;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document")
                .load()
                .migrate();

        dataSource = new CountingDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles CASCADE");
        dataSource.reset();
        repository = new PostgresProfileRepository(new NamedParameterJdbcTemplate(jdbc));
        service = new ProfileSearchService(repository);
    }

    @Test
    void searchProfilesUsesBoundedQueryLoadingAndReportsAccurateCounts() {
        repository.saveProfileAggregate(profileAggregate(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Alpha Profile",
                "alpha@example.test",
                "Java",
                "Montreal"
        ));
        repository.saveProfileAggregate(profileAggregate(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "Beta Profile",
                "beta@example.test",
                "Java",
                "Toronto"
        ));
        repository.saveProfileAggregate(profileAggregate(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "Gamma Profile",
                "gamma@example.test",
                "Python",
                "Remote"
        ));
        dataSource.reset();

        ProfileSearchService.ProfileSearchResult result = service.searchProfiles(new ProfileSearchService.ProfileSearchRequest("java", 1));

        assertEquals(2, result.totalMatches());
        assertEquals(1, result.returnedCount());
        assertEquals(1, result.profiles().size());
        assertEquals(9, dataSource.statementExecutions());
        int boundedRows = dataSource.rowsRead();

        for (int index = 0; index < 40; index++) {
            repository.saveProfileAggregate(profileAggregate(
                    UUID.randomUUID(),
                    "Python Profile " + index,
                    "python-" + index + "@example.test",
                    "Python",
                    "Remote"
            ));
        }
        dataSource.reset();

        ProfileSearchService.ProfileSearchResult grownCorpus = service.searchProfiles(
                new ProfileSearchService.ProfileSearchRequest("java", 1));

        assertEquals(2, grownCorpus.totalMatches());
        assertEquals(result.profiles(), grownCorpus.profiles());
        assertEquals(9, dataSource.statementExecutions());
        assertEquals(boundedRows, dataSource.rowsRead());

        dataSource.reset();
        var firstPage = repository.listProfiles(PageRequest.of(1, null));
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.nextCursor());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(2, dataSource.rowsRead());

        var secondPage = repository.listProfiles(PageRequest.of(1, firstPage.nextCursor()));
        assertEquals(1, secondPage.items().size());
        assertNotEquals(firstPage.items().getFirst().id(), secondPage.items().getFirst().id());

        assertEquals(null, repository.listProfiles(PageRequest.of(100, null)).nextCursor());
        assertEquals(0, service.searchProfiles(new ProfileSearchService.ProfileSearchRequest("rust", 1)).totalMatches());
    }

    private static ProfileAggregate profileAggregate(UUID profileId, String fullName, String email, String skill, String location) {
        UUID projectId = UUID.randomUUID();
        List<ProjectTechnology> projectTechnologies = "Java".equals(skill)
                ? List.of(
                new ProjectTechnology(UUID.randomUUID(), projectId, "PostgreSQL", "postgresql", 0, NOW),
                new ProjectTechnology(UUID.randomUUID(), projectId, "Java", "java", 1, NOW)
        )
                : List.of(new ProjectTechnology(UUID.randomUUID(), projectId, "PostgreSQL", "postgresql", 0, NOW));
        return new ProfileAggregate(
                new UserProfile(profileId, fullName, email, "Backend systems", null, NOW, NOW),
                List.of(new ProfileContact(UUID.randomUUID(), profileId, "location", location, "home", NOW, NOW)),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), profileId, skill, skill.toLowerCase(), "backend", 0, NOW)),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ProfileProject(
                        projectId,
                        profileId,
                        fullName + " Project",
                        "https://example.test/" + fullName.toLowerCase().replace(" ", "-"),
                        skill + " project",
                        projectTechnologies,
                        0,
                        NOW
                )),
                projectTechnologies
        );
    }
}

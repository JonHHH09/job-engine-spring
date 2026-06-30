package org.instruct.jobenginespring.domain.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileRecordsTests {

    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-06-30T13:30:00Z");

    @Test
    void profileAggregateNormalizesNullableCollectionsToImmutableEmptyLists() {
        ProfileAggregate aggregate = new ProfileAggregate(
                sampleProfile(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(List.of(), aggregate.contacts());
        assertEquals(List.of(), aggregate.links());
        assertEquals(List.of(), aggregate.skills());
        assertEquals(List.of(), aggregate.languages());
        assertEquals(List.of(), aggregate.education());
        assertEquals(List.of(), aggregate.experiences());
        assertEquals(List.of(), aggregate.projects());
        assertThrows(UnsupportedOperationException.class, () -> aggregate.skills().add(sampleSkill()));
    }

    @Test
    void profileAggregateKeepsProfileGraphImmutable() {
        UserProfile profile = sampleProfile();
        Education education = new Education(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                PROFILE_ID,
                "Example University",
                "BSc",
                "Computer Science",
                "Remote",
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2024-01-01"),
                "Distributed systems",
                NOW
        );
        Experience experience = new Experience(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                PROFILE_ID,
                "Company XYZ",
                "Java Developer",
                "Remote",
                LocalDate.parse("2024-01-01"),
                null,
                "Built Spring services",
                1,
                NOW
        );
        ProfileProject project = sampleProject();
        ProjectTechnology technology = new ProjectTechnology(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                PROJECT_ID,
                "Spring Boot",
                "spring boot",
                1,
                NOW
        );

        ProfileAggregate aggregate = new ProfileAggregate(
                profile,
                List.of(sampleContact()),
                List.of(sampleLink()),
                List.of(sampleSkill()),
                List.of(sampleLanguage()),
                List.of(education),
                List.of(experience),
                List.of(project)
        );

        assertEquals(profile, aggregate.profile());
        assertEquals(List.of(sampleContact()), aggregate.contacts());
        assertEquals(List.of(sampleLink()), aggregate.links());
        assertEquals(List.of(sampleSkill()), aggregate.skills());
        assertEquals(List.of(sampleLanguage()), aggregate.languages());
        assertEquals(List.of(education), aggregate.education());
        assertEquals(List.of(experience), aggregate.experiences());
        assertEquals(List.of(project), aggregate.projects());
        assertEquals(List.of(technology), aggregate.projects().getFirst().technologies());
        assertThrows(UnsupportedOperationException.class, () -> aggregate.projects().clear());
        assertThrows(UnsupportedOperationException.class, () -> aggregate.projects().getFirst().technologies().clear());
    }

    @Test
    void requiredProfileFieldsRejectBlankOrMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> new UserProfile(
                PROFILE_ID,
                " ",
                "john.doe@example.com",
                null,
                null,
                NOW,
                NOW,
                null
        ));
        assertThrows(NullPointerException.class, () -> new Education(
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                NOW
        ));
    }

    @Test
    void normalizedChildRecordsRejectBlankRequiredValues() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileContact(
                UUID.randomUUID(),
                PROFILE_ID,
                " ",
                "Montreal",
                null,
                NOW,
                NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProfileLink(
                UUID.randomUUID(),
                PROFILE_ID,
                "github",
                " ",
                null,
                NOW,
                NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSkill(
                UUID.randomUUID(),
                PROFILE_ID,
                "Java",
                " ",
                null,
                1,
                NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProfileLanguage(
                UUID.randomUUID(),
                PROFILE_ID,
                " ",
                "english",
                "Fluent",
                1,
                NOW
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProjectTechnology(
                UUID.randomUUID(),
                PROJECT_ID,
                "Java",
                " ",
                1,
                NOW
        ));
    }

    private static UserProfile sampleProfile() {
        return new UserProfile(
                PROFILE_ID,
                "John Doe",
                "john.doe@example.com",
                "Backend developer",
                null,
                NOW,
                NOW,
                null
        );
    }

    private static ProfileContact sampleContact() {
        return new ProfileContact(
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                PROFILE_ID,
                "location",
                "Remote",
                "Primary location",
                NOW,
                NOW
        );
    }

    private static ProfileLink sampleLink() {
        return new ProfileLink(
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                PROFILE_ID,
                "portfolio",
                "https://example.com",
                "Portfolio",
                NOW,
                NOW
        );
    }

    private static ProfileSkill sampleSkill() {
        return new ProfileSkill(
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                PROFILE_ID,
                "Java",
                "java",
                "backend",
                1,
                NOW
        );
    }

    private static ProfileLanguage sampleLanguage() {
        return new ProfileLanguage(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                PROFILE_ID,
                "English",
                "english",
                "Fluent",
                1,
                NOW
        );
    }

    private static ProfileProject sampleProject() {
        return new ProfileProject(
                PROJECT_ID,
                PROFILE_ID,
                "Portfolio System",
                "https://example.com",
                "Profile-safe sample project",
                List.of(new ProjectTechnology(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        PROJECT_ID,
                        "Spring Boot",
                        "spring boot",
                        1,
                        NOW
                )),
                1,
                NOW
        );
    }
}

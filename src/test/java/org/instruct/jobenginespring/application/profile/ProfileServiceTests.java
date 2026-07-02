package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.EducationWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ExperienceWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LanguageWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-30T18:30:00Z");

    private final FakeProfileRepository repository = new FakeProfileRepository();
    private final ProfileService service = new ProfileService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createProfilePersistsNormalizedAggregate() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                "Builds MCP-native systems",
                List.of(new ContactWriteRequest(null, "Location", "Montreal", "home")),
                null,
                List.of(new SkillWriteRequest(null, "Spring AI", null, "backend", 1)),
                null,
                null,
                null,
                null
        ));

        assertNotNull(created.profile().id());
        assertEquals("Agentic Dev", created.profile().fullName());
        assertEquals(NOW, created.profile().createdAt());
        assertEquals("location", created.contacts().getFirst().contactType());
        assertEquals("spring ai", created.skills().getFirst().normalizedSkill());
        assertEquals(List.of(created.profile()), service.listProfiles());
    }

    @Test
    void createProfileMapsEverySupportedChildCollection() {
        UUID contactId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID linkId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID skillId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID languageId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID educationId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID experienceId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID projectId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID technologyId = UUID.fromString("88888888-8888-8888-8888-888888888888");

        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                "Complete child graph",
                List.of(new ContactWriteRequest(contactId, " Phone ", "555-0100", "mobile")),
                List.of(new LinkWriteRequest(linkId, " Portfolio ", "https://example.test", "site")),
                List.of(new SkillWriteRequest(skillId, "Spring AI", " Spring AI ", "backend", 1)),
                List.of(new LanguageWriteRequest(languageId, "English", null, "Fluent", 2)),
                List.of(new EducationWriteRequest(
                        educationId,
                        "Example University",
                        "BSc",
                        "Computer Science",
                        "Remote",
                        LocalDate.parse("2020-01-01"),
                        LocalDate.parse("2024-01-01"),
                        "Distributed systems"
                )),
                List.of(new ExperienceWriteRequest(
                        experienceId,
                        "Example Corp",
                        "Java Developer",
                        "Remote",
                        LocalDate.parse("2024-01-01"),
                        null,
                        "Built services",
                        3
                )),
                List.of(new ProjectWriteRequest(
                        projectId,
                        "Profile Repository",
                        "https://example.test/profile-repository",
                        "Repository work",
                        4,
                        List.of(new ProjectTechnologyWriteRequest(technologyId, "PostgreSQL", null, 5))
                ))
        ));

        assertEquals(contactId, created.contacts().getFirst().id());
        assertEquals("phone", created.contacts().getFirst().contactType());
        assertEquals(linkId, created.links().getFirst().id());
        assertEquals("portfolio", created.links().getFirst().linkType());
        assertEquals(skillId, created.skills().getFirst().id());
        assertEquals("spring ai", created.skills().getFirst().normalizedSkill());
        assertEquals(languageId, created.languages().getFirst().id());
        assertEquals("english", created.languages().getFirst().normalizedLanguage());
        assertEquals(educationId, created.education().getFirst().id());
        assertEquals("Distributed systems", created.education().getFirst().relevantFocus());
        assertEquals(experienceId, created.experiences().getFirst().id());
        assertEquals(3, created.experiences().getFirst().displayOrder());
        assertEquals(projectId, created.projects().getFirst().id());
        assertEquals(technologyId, created.projects().getFirst().technologies().getFirst().id());
        assertEquals("postgresql", created.projectTechnologies().getFirst().normalizedTechnology());
    }

    @Test
    void updateProfileReplacesProfileOwnedChildren() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                "Initial",
                List.of(new ContactWriteRequest(null, "location", "Remote", null)),
                null,
                null,
                null,
                null,
                null,
                null
        ));

        ProfileAggregate updated = service.updateProfile(created.profile().id(), new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                "Updated",
                null,
                null,
                List.of(new SkillWriteRequest(null, "Java", null, "backend", 2)),
                null,
                null,
                null,
                null
        ));

        assertEquals(created.profile().id(), updated.profile().id());
        assertEquals(created.profile().createdAt(), updated.profile().createdAt());
        assertEquals("Updated", updated.profile().summary());
        assertEquals(List.of(), updated.contacts());
        assertEquals("java", updated.skills().getFirst().normalizedSkill());
        assertEquals(2, updated.skills().getFirst().displayOrder());
    }

    @Test
    void getAndDeleteProfileUseRepositoryState() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertTrue(service.getProfile(created.profile().id()).isPresent());
        assertTrue(service.deleteProfile(created.profile().id()));
        assertFalse(service.getProfile(created.profile().id()).isPresent());
        assertFalse(service.deleteProfile(created.profile().id()));
    }

    @Test
    void updateMissingProfileFails() {
        UUID missingProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ProfileNotFoundException exception = assertThrows(ProfileNotFoundException.class, () -> service.updateProfile(missingProfileId, new ProfileWriteRequest(
                "Missing",
                "missing@example.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )));

        assertEquals("not_found", exception.errorCode().code());
        assertEquals("Profile not found: " + missingProfileId, exception.safeMessage());
        assertEquals(Map.of("resource", "profile", "profileId", missingProfileId.toString()), exception.details());
    }

    @Test
    void rejectsNullUseCaseInputs() {
        ProfileWriteRequest request = new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertInvalidProfileWriteRequest(null, "request", "must not be null");
        assertThrows(NullPointerException.class, () -> service.getProfile(null));
        assertThrows(NullPointerException.class, () -> service.updateProfile(null, request));
        ApplicationException updateException = assertThrows(ApplicationException.class, () -> service.updateProfile(UUID.randomUUID(), null));
        assertEquals("validation_error", updateException.errorCode().code());
        assertEquals(Map.of("field", "request", "reason", "must not be null"), updateException.details());
        assertThrows(NullPointerException.class, () -> service.deleteProfile(null));
    }

    @Test
    void rejectsInvalidProfileWriteRequestsBeforePersistence() {
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest(" ", "agentic@example.com", null, null, null, null, null, null, null, null),
                "fullName",
                "must not be blank"
        );
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest("Agentic Dev", "not-an-email", null, null, null, null, null, null, null, null),
                "email",
                "must be a valid email address"
        );
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null,
                        List.of(new ContactWriteRequest(null, " ", "Montreal", null)), null, null, null, null, null, null),
                "contacts[0].contactType",
                "must not be blank"
        );
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null,
                        List.of(new SkillWriteRequest(null, "Java", null, "backend", 0),
                                new SkillWriteRequest(null, " java ", null, "backend", 1)),
                        null, null, null, null),
                "skills[1].normalizedSkill",
                "duplicates another normalized skill in this request"
        );
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, null, null,
                        List.of(new ExperienceWriteRequest(null, "Example Corp", "Developer", null,
                                LocalDate.parse("2026-01-01"), LocalDate.parse("2025-01-01"), null, 0)), null),
                "experiences[0].endDate",
                "must not be before startDate"
        );
        assertInvalidProfileWriteRequest(
                new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, null, null, null,
                        List.of(new ProjectWriteRequest(null, "Project", null, null, 0,
                                List.of(new ProjectTechnologyWriteRequest(null, "PostgreSQL", null, 0),
                                        new ProjectTechnologyWriteRequest(null, "postgresql", null, 1))))),
                "projects[0].technologies[1].normalizedTechnology",
                "duplicates another project technology in this request"
        );

        assertEquals(List.of(), repository.listProfiles());
    }

    @Test
    void updateValidatesRequestBeforeMissingProfileLookup() {
        UUID missingProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProfile(
                missingProfileId,
                new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null,
                        List.of(new SkillWriteRequest(null, "Java", null, "backend", -1)), null, null, null, null)
        ));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(Map.of("field", "skills[0].displayOrder", "reason", "must be greater than or equal to 0"), exception.details());
    }

    private void assertInvalidProfileWriteRequest(ProfileWriteRequest request, String field, String reason) {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.createProfile(request));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("Invalid profile write request", exception.safeMessage());
        assertEquals(Map.of("field", field, "reason", reason), exception.details());
    }

    private static final class FakeProfileRepository implements ProfileRepository {
        private final Map<UUID, ProfileAggregate> aggregates = new LinkedHashMap<>();

        @Override
        public List<UserProfile> listProfiles() {
            return aggregates.values().stream().map(ProfileAggregate::profile).toList();
        }

        @Override
        public Optional<UserProfile> findProfileById(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId)).map(ProfileAggregate::profile);
        }

        @Override
        public List<ProfileContact> listContacts(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::contacts).orElse(List.of());
        }

        @Override
        public List<ProfileLink> listLinks(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::links).orElse(List.of());
        }

        @Override
        public List<ProfileSkill> listSkills(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::skills).orElse(List.of());
        }

        @Override
        public List<ProfileLanguage> listLanguages(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::languages).orElse(List.of());
        }

        @Override
        public List<Education> listEducation(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::education).orElse(List.of());
        }

        @Override
        public List<Experience> listExperiences(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::experiences).orElse(List.of());
        }

        @Override
        public List<ProfileProject> listProjects(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::projects).orElse(List.of());
        }

        @Override
        public List<ProjectTechnology> listProjectTechnologies(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::projectTechnologies).orElse(List.of());
        }

        @Override
        public ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate) {
            aggregates.put(aggregate.profile().id(), aggregate);
            return aggregate;
        }

        @Override
        public boolean deleteProfile(UUID profileId) {
            return aggregates.remove(profileId) != null;
        }

        private Optional<ProfileAggregate> aggregate(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId));
        }
    }
}

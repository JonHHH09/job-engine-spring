package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;
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

        assertThrows(ProfileNotFoundException.class, () -> service.updateProfile(missingProfileId, new ProfileWriteRequest(
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

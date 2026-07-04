package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileIdentityMatcherTests {

    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final FakeProfileRepository repository = new FakeProfileRepository();
    private final ProfileIdentityMatcher matcher = new ProfileIdentityMatcher(repository);

    @Test
    void findsStrongMatchByCanonicalEmailAndNormalizedLink() {
        repository.candidates = List.of(
                new ProfileIdentityCandidate(PROFILE_ID, "email"),
                new ProfileIdentityCandidate(PROFILE_ID, "link:linkedin")
        );

        ProfileIdentityMatcher.ProfileIdentityMatch match = matcher.findStrongMatch(new ProfileWriteRequest(
                "Agentic Dev",
                " Agentic@Example.Test ",
                null,
                List.of(),
                List.of(new LinkWriteRequest(null, " LinkedIn ", "https://LINKEDIN.com/in/example/?trk=public", "profile")),
                List.of(), List.of(), List.of(), List.of(), List.of()
        )).orElseThrow();

        assertEquals(PROFILE_ID, match.profileId());
        assertEquals(List.of("email", "link:linkedin"), match.matchedOn());
        assertFalse(match.ambiguous());
        assertEquals("agentic@example.test", repository.lastSearch.email());
        assertEquals("linkedin", repository.lastSearch.links().getFirst().linkType());
        assertEquals("https://linkedin.com/in/example", repository.lastSearch.links().getFirst().normalizedUrl());
    }

    @Test
    void reportsAmbiguousWhenMultipleProfilesMatchStrongIdentity() {
        UUID otherProfileId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        repository.candidates = List.of(
                new ProfileIdentityCandidate(PROFILE_ID, "email"),
                new ProfileIdentityCandidate(otherProfileId, "link:github")
        );

        ProfileIdentityMatcher.ProfileIdentityMatch match = matcher.findStrongMatch(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.test",
                null,
                List.of(),
                List.of(new LinkWriteRequest(null, "github", "github.com/example", "GitHub")),
                List.of(), List.of(), List.of(), List.of(), List.of()
        )).orElseThrow();

        assertEquals(PROFILE_ID, match.profileId());
        assertTrue(match.ambiguous());
    }

    @Test
    void returnsEmptyWhenNoCandidatesMatch() {
        assertTrue(matcher.findStrongMatch(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.test",
                null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        )).isEmpty());
    }

    private static final class FakeProfileRepository implements ProfileRepository {

        private ProfileIdentitySearch lastSearch;
        private List<ProfileIdentityCandidate> candidates = new ArrayList<>();

        @Override
        public List<ProfileIdentityCandidate> findIdentityCandidates(ProfileIdentitySearch search) {
            lastSearch = search;
            return candidates;
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.UserProfile> listProfiles() {
            return List.of();
        }

        @Override
        public java.util.Optional<org.instruct.jobenginespring.domain.profile.UserProfile> findProfileById(UUID profileId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProfileContact> listContacts(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProfileLink> listLinks(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProfileSkill> listSkills(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProfileLanguage> listLanguages(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.Education> listEducation(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.Experience> listExperiences(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProfileProject> listProjects(UUID profileId) {
            return List.of();
        }

        @Override
        public List<org.instruct.jobenginespring.domain.profile.ProjectTechnology> listProjectTechnologies(UUID profileId) {
            return List.of();
        }

        @Override
        public org.instruct.jobenginespring.domain.profile.ProfileAggregate saveProfileAggregate(
                org.instruct.jobenginespring.domain.profile.ProfileAggregate aggregate
        ) {
            return aggregate;
        }

        @Override
        public boolean deleteProfile(UUID profileId) {
            return false;
        }
    }
}

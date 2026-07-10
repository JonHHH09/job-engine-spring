package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchRequest;
import org.instruct.jobenginespring.application.profile.ProfileSearchService.ProfileSearchResult;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSearchServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-06T18:30:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final FakeProfileRepository repository = new FakeProfileRepository();
    private final ProfileSearchService service = new ProfileSearchService(repository);

    @Test
    void searchesProfilesAcrossNormalizedProfileGraphWithoutReturningRawResumeText() {
        repository.saveProfileAggregate(completeAggregate(PROFILE_ID, "Agentic Dev", "agentic@example.test"));
        repository.saveProfileAggregate(minimalAggregate(OTHER_PROFILE_ID, "Backend Engineer", "backend@example.test"));

        ProfileSearchResult result = service.searchProfiles(new ProfileSearchRequest("spring ai developer montreal", 10));

        assertEquals("spring ai developer montreal", result.query());
        assertEquals(List.of("spring", "ai", "developer", "montreal"), result.queryTokens());
        assertEquals(1, result.totalMatches());
        assertEquals(1, result.returnedCount());
        assertEquals(PROFILE_ID, result.profiles().getFirst().profile().id());
        assertTrue(result.profiles().getFirst().score() > 0);
        assertEquals(List.of(
                "profile.fullName",
                "contacts",
                "skills",
                "experience.title",
                "experience.location",
                "experience.description",
                "projects"
        ), result.profiles().getFirst().matchedFields());
    }

    @Test
    void ranksByScoreThenNameAndHonorsLimit() {
        repository.saveProfileAggregate(completeAggregate(PROFILE_ID, "Beta Profile", "beta@example.test"));
        repository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(OTHER_PROFILE_ID, "Alpha Profile", "alpha@example.test", "Java", null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), OTHER_PROFILE_ID, "Java", "java", "backend", 0, NOW)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        ProfileSearchResult limited = service.searchProfiles(new ProfileSearchRequest("java", 1));
        ProfileSearchResult all = service.searchProfiles(new ProfileSearchRequest("java", 10));

        assertEquals(2, limited.totalMatches());
        assertEquals(1, limited.returnedCount());
        assertEquals("Alpha Profile", limited.profiles().getFirst().profile().fullName());
        assertEquals(List.of("Alpha Profile", "Beta Profile"), all.profiles().stream()
                .map(match -> match.profile().fullName())
                .toList());
    }

    @Test
    void ordersEqualScoreMatchesByNameThenId() {
        UUID firstProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondProfileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        repository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(secondProfileId, "Tie Profile", "tie-two@example.test", null, null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), secondProfileId, "Java", "java", "backend", 0, NOW)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        repository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(firstProfileId, "Tie Profile", "tie-one@example.test", null, null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), firstProfileId, "Java", "java", "backend", 0, NOW)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        ProfileSearchResult result = service.searchProfiles(new ProfileSearchRequest("java", 10));

        assertEquals(List.of(firstProfileId, secondProfileId), result.profiles().stream()
                .map(match -> match.profile().id())
                .toList());
    }

    @Test
    void returnsEmptyResultWhenNoProfileMatches() {
        repository.saveProfileAggregate(minimalAggregate(PROFILE_ID, "Agentic Dev", "agentic@example.test"));

        ProfileSearchResult result = service.searchProfiles(new ProfileSearchRequest("kubernetes", null));

        assertEquals("kubernetes", result.query());
        assertEquals(List.of("kubernetes"), result.queryTokens());
        assertEquals(0, result.totalMatches());
        assertEquals(0, result.returnedCount());
        assertEquals(List.of(), result.profiles());
    }

    @Test
    void searchUsesBatchAggregateLoadingInsteadOfPerProfileLookups() {
        repository.saveProfileAggregate(completeAggregate(PROFILE_ID, "Agentic Dev", "agentic@example.test"));
        repository.saveProfileAggregate(minimalAggregate(OTHER_PROFILE_ID, "Backend Engineer", "backend@example.test"));
        repository.listProfileAggregatesCalls = 0;
        repository.findProfileAggregateCalls = 0;

        service.searchProfiles(new ProfileSearchRequest("developer", 10));

        assertEquals(1, repository.listProfileAggregatesCalls);
        assertEquals(0, repository.findProfileAggregateCalls);
    }

    @Test
    void validatesSearchRequests() {
        assertInvalid(null, "request", "must not be null");
        assertInvalid(new ProfileSearchRequest(null, 10), "query", "must not be blank");
        assertInvalid(new ProfileSearchRequest(" ", 10), "query", "must not be blank");
        assertInvalid(new ProfileSearchRequest("---", 10), "query", "must contain searchable text");
        assertInvalid(new ProfileSearchRequest("java", 0), "limit", "must be between 1 and 100");
        assertInvalid(new ProfileSearchRequest("java", 101), "limit", "must be between 1 and 100");
    }

    @Test
    void coversTokenAndRecordDefensiveEdges() throws Exception {
        assertEquals(List.of(), invokeTokens(null));
        assertEquals(List.of(), invokeTokens(" "));
        assertEquals(List.of("java"), invokeTokens("---java---"));
        assertEquals(List.of("c++", "spring", "ai", "resume"), invokeTokens(" C++ / Spring-AI résumé "));
        assertFalse((Boolean) invoke("containsTokenPrefix", new Class<?>[]{java.util.Set.class, String.class}, java.util.Set.of("spring"), "java"));
        assertTrue((Boolean) invoke("containsTokenPrefix", new Class<?>[]{java.util.Set.class, String.class}, java.util.Set.of("postgresql"), "post"));
        assertTrue((Boolean) invoke("containsTokenPrefix", new Class<?>[]{java.util.Set.class, String.class}, java.util.Set.of("post"), "postgresql"));

        ProfileSearchService.ProfileSearchResult nullCollectionsResult = new ProfileSearchService.ProfileSearchResult("q", null, 0, 0, null);
        assertEquals(List.of(), nullCollectionsResult.queryTokens());
        assertEquals(List.of(), nullCollectionsResult.profiles());
        ProfileSearchService.ProfileSearchMatch nullFieldsMatch = new ProfileSearchService.ProfileSearchMatch(
                minimalAggregate(PROFILE_ID, "Agentic Dev", "agentic@example.test").profile(),
                1,
                null
        );
        assertEquals(List.of(), nullFieldsMatch.matchedFields());
        assertThrows(NullPointerException.class, () -> new ProfileSearchService.ProfileSearchMatch(null, 1, List.of()));
    }

    private void assertInvalid(ProfileSearchRequest request, String field, String reason) {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.searchProfiles(request));
        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("Invalid profile search request", exception.safeMessage());
        assertEquals(Map.of("field", field, "reason", reason), exception.details());
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeTokens(String text) throws Exception {
        return (List<String>) invoke("tokens", new Class<?>[]{String.class}, text);
    }

    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ProfileSearchService.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static ProfileAggregate minimalAggregate(UUID profileId, String fullName, String email) {
        return new ProfileAggregate(
                new UserProfile(profileId, fullName, email, null, null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ProfileAggregate completeAggregate(UUID profileId, String fullName, String email) {
        UUID projectId = UUID.randomUUID();
        return new ProfileAggregate(
                new UserProfile(profileId, fullName, email, "Builds MCP-native tools", null, NOW, NOW),
                List.of(new ProfileContact(UUID.randomUUID(), profileId, "location", "Montreal", "home", NOW, NOW)),
                List.of(new ProfileLink(UUID.randomUUID(), profileId, "portfolio", "https://example.test/profile", "site", NOW, NOW)),
                List.of(new ProfileSkill(UUID.randomUUID(), profileId, "Spring AI", "spring ai", "backend", 0, NOW)),
                List.of(new ProfileLanguage(UUID.randomUUID(), profileId, "English", "english", "Fluent", 0, NOW)),
                List.of(new Education(UUID.randomUUID(), profileId, "Example University", "BSc", "Computer Science", "Remote", LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-01"), "Distributed systems", NOW)),
                List.of(new Experience(UUID.randomUUID(), profileId, "Example Corp", "Java Developer", "Montreal", LocalDate.parse("2024-01-01"), null, "Built Spring AI services", 0, NOW)),
                List.of(new ProfileProject(projectId, profileId, "Profile Search", "https://example.test/search", "Spring profile query", List.of(
                        new ProjectTechnology(UUID.randomUUID(), projectId, "PostgreSQL", "postgresql", 0, NOW)
                ), 0, NOW)),
                List.of(new ProjectTechnology(UUID.randomUUID(), projectId, "PostgreSQL", "postgresql", 0, NOW))
        );
    }

    private static final class FakeProfileRepository implements ProfileRepository {
        private final Map<UUID, ProfileAggregate> aggregates = new LinkedHashMap<>();
        private int listProfileAggregatesCalls;
        private int findProfileAggregateCalls;

        @Override
        public List<UserProfile> listProfiles() {
            return aggregates.values().stream().map(ProfileAggregate::profile).toList();
        }

        @Override
        public Optional<UserProfile> findProfileById(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId)).map(ProfileAggregate::profile);
        }

        @Override
        public Optional<ProfileAggregate> findProfileAggregate(UUID profileId) {
            findProfileAggregateCalls++;
            return aggregate(profileId);
        }

        @Override
        public List<ProfileAggregate> listProfileAggregates() {
            listProfileAggregatesCalls++;
            return List.copyOf(aggregates.values());
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

package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.EducationWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ExperienceWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LanguageWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectUpdateRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;
import org.instruct.jobenginespring.application.document.GeneratedResumeAssetService;
import org.instruct.jobenginespring.application.document.GermanCoverLetterPersistenceService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-30T18:30:00Z");

    private final FakeProfileRepository repository = new FakeProfileRepository();
    private final GeneratedResumeAssetService generatedResumeAssetService = mock(GeneratedResumeAssetService.class);
    private final GermanCoverLetterPersistenceService germanCoverLetterPersistenceService = mock(GermanCoverLetterPersistenceService.class);
    private final ProfileService service = new ProfileService(
            repository,
            generatedResumeAssetService,
            germanCoverLetterPersistenceService,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    ProfileServiceTests() {
        when(generatedResumeAssetService.deleteProfile(any()))
                .thenAnswer(invocation -> repository.deleteProfile(invocation.getArgument(0)));
    }

    @Test
    void createProfilePersistsNormalizedAggregate() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                " Agentic Dev ",
                " AGENTIC@EXAMPLE.COM ",
                " Builds MCP-native systems ",
                List.of(new ContactWriteRequest(null, " Location ", " Montreal ", " home ")),
                null,
                List.of(new SkillWriteRequest(null, " Spring AI ", null, " backend ", 1)),
                null,
                null,
                null,
                null
        ));

        assertNotNull(created.profile().id());
        assertEquals("Agentic Dev", created.profile().fullName());
        assertEquals("agentic@example.com", created.profile().email());
        assertEquals("Builds MCP-native systems", created.profile().summary());
        assertEquals(NOW, created.profile().createdAt());
        assertEquals("location", created.contacts().getFirst().contactType());
        assertEquals("Montreal", created.contacts().getFirst().contactValue());
        assertEquals("home", created.contacts().getFirst().label());
        assertEquals("spring ai", created.skills().getFirst().normalizedSkill());
        assertEquals("backend", created.skills().getFirst().category());
        assertEquals(List.of(created.profile()), service.listProfiles());
        assertEquals(List.of(created.profile()), service.listProfiles(1, null).items());
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

        ProfileAggregate updated = service.updateProfile(created.profile().id(), created.profile().revision(), new ProfileWriteRequest(
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
    void updateProfileRejectsStaleExpectedRevisionWithoutReplacingChildren() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial",
                List.of(new ContactWriteRequest(null, "location", "Remote", null)),
                null, null, null, null, null, null
        ));
        ProfileWriteRequest replacement = new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Stale replacement",
                null, null, null, null, null, null, null
        );

        ApplicationException exception = assertThrows(ApplicationException.class, () ->
                service.updateProfile(created.profile().id(), created.profile().revision() + 1, replacement));

        assertEquals("conflict", exception.errorCode().code());
        assertEquals(Map.of(
                "resource", "profile",
                "profileId", created.profile().id().toString(),
                "expectedRevision", "1"
        ), exception.details());
        ProfileAggregate persisted = service.getProfile(created.profile().id()).orElseThrow();
        assertEquals("Initial", persisted.profile().summary());
        assertEquals(List.of("location"), persisted.contacts().stream().map(ProfileContact::contactType).toList());
    }

    @Test
    void updateProjectPatchesOnlyTheSelectedProjectAndReplacesTechnologiesWhenSupplied() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial",
                List.of(new ContactWriteRequest(null, "location", "Remote", null)),
                null, null, null, null, null,
                List.of(new ProjectWriteRequest(
                        UUID.fromString("77777777-7777-7777-7777-777777777777"),
                        "Initial project", "https://example.test/initial", "Initial description", 1,
                        List.of(new ProjectTechnologyWriteRequest(null, "Java", null, 0))
                ))
        ));
        ProfileProject original = created.projects().getFirst();

        ProjectUpdateResult result = service.updateProject(new ProjectUpdateRequest(
                created.profile().id(),
                original.id(),
                created.profile().revision(),
                " Updated project ",
                " https://example.test/updated ",
                " Updated description ",
                2,
                List.of(new ProjectTechnologyWriteRequest(null, " PostgreSQL ", null, 0))
        ));

        assertEquals(created.profile().revision() + 1, result.profileRevision());
        assertEquals("Updated project", result.project().name());
        assertEquals("https://example.test/updated", result.project().url());
        assertEquals("Updated description", result.project().description());
        assertEquals(2, result.project().displayOrder());
        assertEquals(List.of("PostgreSQL"), result.project().technologies().stream()
                .map(ProjectTechnology::technology).toList());
        ProjectUpdateResult preservedTechnologies = service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), original.id(), result.profileRevision(), null, null, null, 3, null
        ));
        assertEquals(List.of("PostgreSQL"), preservedTechnologies.project().technologies().stream()
                .map(ProjectTechnology::technology).toList());
        ProfileAggregate persisted = service.getProfile(created.profile().id()).orElseThrow();
        assertEquals(List.of("location"), persisted.contacts().stream().map(ProfileContact::contactType).toList());
        assertEquals(preservedTechnologies.project(), persisted.projects().getFirst());
    }

    @Test
    void updateProjectRejectsStaleRevisionAndMissingProjectWithoutMutatingProfile() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial", null, null, null, null, null, null,
                List.of(new ProjectWriteRequest(null, "Initial project", null, null, 0, null))
        ));
        UUID projectId = created.projects().getFirst().id();

        ApplicationException stale = assertThrows(ApplicationException.class, () -> service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), projectId, 1L, "Stale", null, null, null, null
        )));
        ApplicationException missing = assertThrows(ApplicationException.class, () -> service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), UUID.randomUUID(), 0L, "Missing", null, null, null, null
        )));

        assertEquals("conflict", stale.errorCode().code());
        assertEquals("not_found", missing.errorCode().code());
        ProfileAggregate persisted = service.getProfile(created.profile().id()).orElseThrow();
        assertEquals(0L, persisted.profile().revision());
        assertEquals("Initial project", persisted.projects().getFirst().name());
    }

    @Test
    void updateProjectRejectsAnotherProjectCanonicalNameAndUrl() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial", null, null, null, null, null, null,
                List.of(
                        new ProjectWriteRequest(null, "First project", "https://example.test/first", null, 0, null),
                        new ProjectWriteRequest(null, "Second project", "https://example.test/second", null, 1, null)
                )
        ));
        ProfileProject first = created.projects().getFirst();
        ProfileProject second = created.projects().get(1);

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), second.id(), created.profile().revision(), " FIRST PROJECT ", first.url(), null, null, null
        )));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(Map.of("field", "name", "reason", "duplicates another project name/url in this profile"), exception.details());
        ProfileAggregate persisted = service.getProfile(created.profile().id()).orElseThrow();
        assertEquals(0L, persisted.profile().revision());
        assertEquals(List.of("First project", "Second project"), persisted.projects().stream().map(ProfileProject::name).toList());

        ProjectUpdateResult nonDuplicate = service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), first.id(), persisted.profile().revision(), null, null, null, 2, null
        ));

        assertEquals(1L, nonDuplicate.profileRevision());
        assertEquals(2, nonDuplicate.project().displayOrder());
    }

    @Test
    void updateProjectValidatesEveryPartialRequestBoundary() {
        UUID profileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID projectId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertInvalidProjectUpdate(null, "request", "must not be null");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(null, projectId, 0L, "Project", null, null, null, null),
                "profileId", "must not be null");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, null, 0L, "Project", null, null, null, null),
                "projectId", "must not be null");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, null, "Project", null, null, null, null),
                "expectedRevision", "must not be null");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, -1L, "Project", null, null, null, null),
                "expectedRevision", "must not be negative");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null, null),
                "request", "must specify at least one project field");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, " ", null, null, null, null),
                "name", "must not be blank");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, " ", null, null, null),
                "url", "must not be blank");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, " ", null, null),
                "description", "must not be blank");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, -1, null),
                "displayOrder", "must be greater than or equal to 0");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        java.util.Collections.singletonList(null)),
                "technologies[0]", "must not be null");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        List.of(new ProjectTechnologyWriteRequest(null, null, null, 0))),
                "technologies[0].technology", "must not be blank");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        List.of(new ProjectTechnologyWriteRequest(null, " ", null, 0))),
                "technologies[0].technology", "must not be blank");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        List.of(new ProjectTechnologyWriteRequest(null, "Java", " ", 0))),
                "technologies[0].normalizedTechnology", "must not be blank when supplied");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        List.of(new ProjectTechnologyWriteRequest(null, "Java", null, -1))),
                "technologies[0].displayOrder", "must be greater than or equal to 0");
        assertInvalidProjectUpdate(new ProjectUpdateRequest(profileId, projectId, 0L, null, null, null, null,
                        List.of(
                                new ProjectTechnologyWriteRequest(null, "Java", "java", 0),
                                new ProjectTechnologyWriteRequest(null, "JAVA", "Java", 1)
                        )),
                "technologies[1].normalizedTechnology", "duplicates another project technology in this request");
    }

    @Test
    void updateProjectReportsMissingProfile() {
        UUID profileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID projectId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ProjectUpdateRequest request = new ProjectUpdateRequest(profileId, projectId, 0L, "Project", null, null, null, List.of());

        ProfileNotFoundException missing = assertThrows(ProfileNotFoundException.class, () -> service.updateProject(request));
        assertEquals(profileId.toString(), missing.details().get("profileId"));
    }

    @Test
    void unsupportedRepositoryFailsClosedForSelectiveProjectUpdates() {
        ProfileRepository unsupported = mock(ProfileRepository.class, CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> unsupported.updateProject(
                new ProfileProject(UUID.randomUUID(), UUID.randomUUID(), "Project", null, null, 0, NOW),
                0L, 1L, NOW, List.of()
        ));
    }

    @Test
    void updateProjectReportsConflictWhenRepositoryCompareAndSetLosesRace() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", null, null, null, null, null, null, null,
                List.of(new ProjectWriteRequest(null, "Project", null, null, 0, null))
        ));
        repository.rejectNextProjectUpdate = true;

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProject(new ProjectUpdateRequest(
                created.profile().id(), created.projects().getFirst().id(), created.profile().revision(),
                "Concurrent", null, null, null, null
        )));

        assertEquals("conflict", exception.errorCode().code());
        assertEquals("Project", service.getProfile(created.profile().id()).orElseThrow().projects().getFirst().name());
    }

    @Test
    void updateProfileValidatesExpectedRevision() {
        ProfileWriteRequest request = new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", null, null, null, null, null, null, null, null
        );

        ApplicationException missing = assertThrows(ApplicationException.class, () ->
                service.updateProfile(UUID.randomUUID(), null, request));
        ApplicationException negative = assertThrows(ApplicationException.class, () ->
                service.updateProfile(UUID.randomUUID(), -1L, request));

        assertEquals(Map.of("field", "expectedRevision", "reason", "must not be null"), missing.details());
        assertEquals(Map.of("field", "expectedRevision", "reason", "must not be negative"), negative.details());
    }

    @Test
    void updateProfileReportsConflictWhenCompareAndSetLosesRace() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial", null, null, null, null, null, null, null
        ));
        repository.rejectNextReplacement = true;

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProfile(
                created.profile().id(), created.profile().revision(), new ProfileWriteRequest(
                        "Agentic Dev", "agentic@example.com", "Concurrent", null, null, null, null, null, null, null
                )
        ));

        assertEquals("conflict", exception.errorCode().code());
        assertEquals("Initial", service.getProfile(created.profile().id()).orElseThrow().profile().summary());
    }

    @Test
    void updateProfileMapsRevisionExhaustionToSanitizedConflict() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", "Initial", null, null, null, null, null, null, null
        ));
        UserProfile profile = created.profile();
        repository.saveProfileAggregate(new ProfileAggregate(
                new UserProfile(profile.id(), profile.fullName(), profile.email(), profile.summary(),
                        profile.rawResumeText(), profile.createdAt(), profile.updatedAt(), profile.embedding(), Long.MAX_VALUE),
                created.contacts(), created.links(), created.skills(), created.languages(), created.education(),
                created.experiences(), created.projects(), created.projectTechnologies()
        ));

        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProfile(
                profile.id(), Long.MAX_VALUE, new ProfileWriteRequest(
                        "Agentic Dev", "agentic@example.com", "Overflow", null, null, null, null, null, null, null
                )
        ));

        assertEquals("conflict", exception.errorCode().code());
        assertEquals(Map.of(
                "resource", "profile",
                "profileId", profile.id().toString(),
                "expectedRevision", Long.toString(Long.MAX_VALUE)
        ), exception.details());
        assertEquals(Long.MAX_VALUE, repository.findProfileById(profile.id()).orElseThrow().revision());
    }

    @Test
    void defaultProfileRepositoryReplacementRejectsStaleRevision() {
        ProfileAggregate created = service.createProfile(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", null, null, null, null, null, null, null, null
        ));

        assertEquals(Optional.empty(), repository.replaceProfileAggregate(created, created.profile().revision() + 1));
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
        verify(germanCoverLetterPersistenceService, times(2)).lockAndFindAllByProfileId(created.profile().id());
        verify(germanCoverLetterPersistenceService).cleanupDeletedVariants(List.of());
    }

    @Test
    void updateMissingProfileFails() {
        UUID missingProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ProfileNotFoundException exception = assertThrows(ProfileNotFoundException.class, () -> service.updateProfile(missingProfileId, 0L, new ProfileWriteRequest(
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
        assertThrows(NullPointerException.class, () -> service.updateProfile(null, 0L, request));
        ApplicationException updateException = assertThrows(ApplicationException.class, () -> service.updateProfile(UUID.randomUUID(), 0L, null));
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
                0L,
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

    private void assertInvalidProjectUpdate(ProjectUpdateRequest request, String field, String reason) {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> service.updateProject(request));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals(Map.of("field", field, "reason", reason), exception.details());
    }

    private static final class FakeProfileRepository implements ProfileRepository {
        private final Map<UUID, ProfileAggregate> aggregates = new LinkedHashMap<>();
        private boolean rejectNextReplacement;
        private boolean rejectNextProjectUpdate;

        @Override
        public org.instruct.jobenginespring.application.pagination.Page<UserProfile> listProfiles(
                org.instruct.jobenginespring.application.pagination.PageRequest request) {
            return new org.instruct.jobenginespring.application.pagination.Page<>(aggregates.values().stream()
                    .limit(request.limit()).map(ProfileAggregate::profile).toList(), null);
        }

        @Override
        public Optional<UserProfile> findProfileById(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId)).map(ProfileAggregate::profile);
        }

        @Override
        public org.instruct.jobenginespring.application.pagination.Page<ProfileAggregate> listProfileAggregates(
                org.instruct.jobenginespring.application.pagination.PageRequest request) {
            return new org.instruct.jobenginespring.application.pagination.Page<>(aggregates.values().stream()
                    .limit(request.limit()).toList(), null);
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
        public Optional<ProfileAggregate> replaceProfileAggregate(ProfileAggregate aggregate, long expectedRevision) {
            if (rejectNextReplacement) {
                rejectNextReplacement = false;
                return Optional.empty();
            }
            return ProfileRepository.super.replaceProfileAggregate(aggregate, expectedRevision);
        }

        @Override
        public Optional<ProjectUpdateResult> updateProject(
                ProfileProject project,
                long expectedRevision,
                long newRevision,
                Instant profileUpdatedAt,
                List<ProjectTechnology> replacementTechnologies
        ) {
            ProfileAggregate existing = aggregates.get(project.profileId());
            if (rejectNextProjectUpdate) {
                rejectNextProjectUpdate = false;
                return Optional.empty();
            }
            if (existing == null || existing.profile().revision() != expectedRevision
                    || existing.projects().stream().noneMatch(candidate -> candidate.id().equals(project.id()))) {
                return Optional.empty();
            }
            UserProfile currentProfile = existing.profile();
            UserProfile updatedProfile = new UserProfile(
                    currentProfile.id(), currentProfile.fullName(), currentProfile.email(), currentProfile.summary(),
                    currentProfile.rawResumeText(), currentProfile.createdAt(), profileUpdatedAt,
                    currentProfile.embedding(), newRevision
            );
            List<ProfileProject> projects = existing.projects().stream()
                    .map(candidate -> candidate.id().equals(project.id()) ? project : candidate)
                    .toList();
            List<ProjectTechnology> technologies = projects.stream()
                    .flatMap(candidate -> candidate.technologies().stream())
                    .toList();
            aggregates.put(project.profileId(), new ProfileAggregate(
                    updatedProfile, existing.contacts(), existing.links(), existing.skills(), existing.languages(),
                    existing.education(), existing.experiences(), projects, technologies
            ));
            return Optional.of(new ProjectUpdateResult(project, updatedProfile.revision()));
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

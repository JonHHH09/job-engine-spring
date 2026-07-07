package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.security.McpAccessPolicy;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application use cases for manipulating profile-owned data. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ProfileService {

    @NonNull
    private final ProfileRepository profileRepository;
    @NonNull
    private final McpAccessPolicy accessPolicy;
    private Clock clock = Clock.systemUTC();

    ProfileService(ProfileRepository profileRepository, Clock clock) {
        this(profileRepository, McpAccessPolicy.permitAllForTests());
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    ProfileService(ProfileRepository profileRepository, McpAccessPolicy accessPolicy, Clock clock) {
        this(profileRepository, accessPolicy);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<UserProfile> listProfiles(String accessToken) {
        accessPolicy.authorize(accessToken, "list_profiles");
        return profileRepository.listProfiles();
    }

    public void authorizeAccess(String accessToken, String operation) {
        accessPolicy.authorize(accessToken, operation);
    }

    @Transactional(readOnly = true)
    public Optional<ProfileAggregate> getProfile(UUID profileId, String accessToken) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        accessPolicy.authorize(accessToken, "get_profile");
        return profileRepository.findProfileAggregate(profileId);
    }

    @Transactional
    public ProfileAggregate createProfile(ProfileWriteRequest request) {
        accessPolicy.authorize(request == null ? null : request.accessToken(), "create_profile");
        ProfileWriteValidator.validate(request);
        ProfileWriteRequest safeRequest = ProfileWriteCanonicalizer.canonicalize(request);
        Instant now = clock.instant();
        UUID profileId = UUID.randomUUID();
        ProfileAggregate aggregate = toAggregate(profileId, safeRequest, now, now);
        return profileRepository.saveProfileAggregate(aggregate);
    }

    @Transactional
    public ProfileAggregate updateProfile(UUID profileId, ProfileWriteRequest request) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        accessPolicy.authorize(request == null ? null : request.accessToken(), "update_profile");
        ProfileWriteValidator.validate(request);
        ProfileWriteRequest safeRequest = ProfileWriteCanonicalizer.canonicalize(request);
        UserProfile existing = profileRepository.findProfileById(profileId)
                .orElseThrow(() -> new ProfileNotFoundException(profileId));
        ProfileAggregate aggregate = toAggregate(profileId, safeRequest, existing.createdAt(), clock.instant());
        return profileRepository.saveProfileAggregate(aggregate);
    }

    @Transactional
    public boolean deleteProfile(UUID profileId, String accessToken) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        accessPolicy.authorize(accessToken, "delete_profile");
        return profileRepository.deleteProfile(profileId);
    }

    private ProfileAggregate toAggregate(UUID profileId, ProfileWriteRequest request, Instant createdAt, Instant updatedAt) {
        UserProfile profile = new UserProfile(
                profileId,
                request.fullName(),
                request.email(),
                request.summary(),
                null,
                createdAt,
                updatedAt,
                null
        );
        List<ProfileProject> projectRecords = projects(profileId, request.projects(), createdAt);
        return new ProfileAggregate(
                profile,
                contacts(profileId, request.contacts(), createdAt, updatedAt),
                links(profileId, request.links(), createdAt, updatedAt),
                skills(profileId, request.skills(), createdAt),
                languages(profileId, request.languages(), createdAt),
                education(profileId, request.education(), createdAt),
                experiences(profileId, request.experiences(), createdAt),
                projectRecords,
                projectRecords.stream().flatMap(project -> project.technologies().stream()).toList()
        );
    }

    private static List<ProfileContact> contacts(UUID profileId, List<ContactWriteRequest> contacts, Instant createdAt, Instant updatedAt) {
        return contacts.stream()
                .map(contact -> new ProfileContact(
                        newId(contact.id()),
                        profileId,
                        contact.contactType(),
                        contact.contactValue(),
                        contact.label(),
                        createdAt,
                        updatedAt
                ))
                .toList();
    }

    private static List<ProfileLink> links(UUID profileId, List<LinkWriteRequest> links, Instant createdAt, Instant updatedAt) {
        return links.stream()
                .map(link -> new ProfileLink(
                        newId(link.id()),
                        profileId,
                        link.linkType(),
                        link.url(),
                        link.label(),
                        createdAt,
                        updatedAt
                ))
                .toList();
    }

    private static List<ProfileSkill> skills(UUID profileId, List<SkillWriteRequest> skills, Instant createdAt) {
        return skills.stream()
                .map(skill -> new ProfileSkill(
                        newId(skill.id()),
                        profileId,
                        skill.skill(),
                        skill.normalizedSkill(),
                        skill.category(),
                        skill.displayOrder(),
                        createdAt
                ))
                .toList();
    }

    private static List<ProfileLanguage> languages(UUID profileId, List<LanguageWriteRequest> languages, Instant createdAt) {
        return languages.stream()
                .map(language -> new ProfileLanguage(
                        newId(language.id()),
                        profileId,
                        language.language(),
                        language.normalizedLanguage(),
                        language.proficiency(),
                        language.displayOrder(),
                        createdAt
                ))
                .toList();
    }

    private static List<Education> education(UUID profileId, List<EducationWriteRequest> education, Instant createdAt) {
        return education.stream()
                .map(item -> new Education(
                        newId(item.id()),
                        profileId,
                        item.institution(),
                        item.degree(),
                        item.field(),
                        item.location(),
                        item.startDate(),
                        item.endDate(),
                        item.relevantFocus(),
                        createdAt
                ))
                .toList();
    }

    private static List<Experience> experiences(UUID profileId, List<ExperienceWriteRequest> experiences, Instant createdAt) {
        return experiences.stream()
                .map(item -> new Experience(
                        newId(item.id()),
                        profileId,
                        item.company(),
                        item.title(),
                        item.location(),
                        item.startDate(),
                        item.endDate(),
                        item.description(),
                        item.displayOrder(),
                        createdAt
                ))
                .toList();
    }

    private static List<ProfileProject> projects(UUID profileId, List<ProjectWriteRequest> projects, Instant createdAt) {
        return projects.stream()
                .map(project -> {
                    UUID projectId = newId(project.id());
                    return new ProfileProject(
                            projectId,
                            profileId,
                            project.name(),
                            project.url(),
                            project.description(),
                            technologies(projectId, project.technologies(), createdAt),
                            project.displayOrder(),
                            createdAt
                    );
                })
                .toList();
    }


    private static List<ProjectTechnology> technologies(UUID projectId, List<ProjectTechnologyWriteRequest> technologies, Instant createdAt) {
        return technologies.stream()
                .map(technology -> new ProjectTechnology(
                        newId(technology.id()),
                        projectId,
                        technology.technology(),
                        technology.normalizedTechnology(),
                        technology.displayOrder(),
                        createdAt
                ))
                .toList();
    }

    private static UUID newId(UUID id) {
        return id == null ? UUID.randomUUID() : id;
    }

    public record ProfileWriteRequest(
            String fullName,
            String email,
            String summary,
            List<ContactWriteRequest> contacts,
            List<LinkWriteRequest> links,
            List<SkillWriteRequest> skills,
            List<LanguageWriteRequest> languages,
            List<EducationWriteRequest> education,
            List<ExperienceWriteRequest> experiences,
            List<ProjectWriteRequest> projects,
            String accessToken
    ) {
        public ProfileWriteRequest(
                String fullName,
                String email,
                String summary,
                List<ContactWriteRequest> contacts,
                List<LinkWriteRequest> links,
                List<SkillWriteRequest> skills,
                List<LanguageWriteRequest> languages,
                List<EducationWriteRequest> education,
                List<ExperienceWriteRequest> experiences,
                List<ProjectWriteRequest> projects
        ) {
            this(fullName, email, summary, contacts, links, skills, languages, education, experiences, projects, null);
        }

        public ProfileWriteRequest withAccessToken(String accessToken) {
            return new ProfileWriteRequest(
                    fullName,
                    email,
                    summary,
                    contacts,
                    links,
                    skills,
                    languages,
                    education,
                    experiences,
                    projects,
                    accessToken
            );
        }
    }

    public record ContactWriteRequest(UUID id, String contactType, String contactValue, String label) {
    }

    public record LinkWriteRequest(UUID id, String linkType, String url, String label) {
    }

    public record SkillWriteRequest(UUID id, String skill, String normalizedSkill, String category, int displayOrder) {
    }

    public record LanguageWriteRequest(UUID id, String language, String normalizedLanguage, String proficiency, int displayOrder) {
    }

    public record EducationWriteRequest(
            UUID id,
            String institution,
            String degree,
            String field,
            String location,
            LocalDate startDate,
            LocalDate endDate,
            String relevantFocus
    ) {
    }

    public record ExperienceWriteRequest(
            UUID id,
            String company,
            String title,
            String location,
            LocalDate startDate,
            LocalDate endDate,
            String description,
            int displayOrder
    ) {
    }

    public record ProjectWriteRequest(
            UUID id,
            String name,
            String url,
            String description,
            int displayOrder,
            List<ProjectTechnologyWriteRequest> technologies
    ) {
    }

    public record ProjectTechnologyWriteRequest(UUID id, String technology, String normalizedTechnology, int displayOrder) {
    }

    public static final class ProfileNotFoundException extends ApplicationException {
        public ProfileNotFoundException(UUID profileId) {
            super(
                    ApplicationErrorCode.NOT_FOUND,
                    "Profile not found: " + profileId,
                    Map.of("resource", "profile", "profileId", String.valueOf(profileId)),
                    null
            );
        }
    }
}

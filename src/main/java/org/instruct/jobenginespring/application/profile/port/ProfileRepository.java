package org.instruct.jobenginespring.application.profile.port;

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
import org.instruct.jobenginespring.application.profile.ProfileIdentityCandidate;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application port for profile-owned data.
 *
 * <p>Outbound adapters implement this port without exposing persistence row/entity types to the
 * application or domain layers.
 */
public interface ProfileRepository {

    List<UserProfile> listProfiles();

    Optional<UserProfile> findProfileById(UUID profileId);

    List<ProfileContact> listContacts(UUID profileId);

    List<ProfileLink> listLinks(UUID profileId);

    List<ProfileSkill> listSkills(UUID profileId);

    List<ProfileLanguage> listLanguages(UUID profileId);

    List<Education> listEducation(UUID profileId);

    List<Experience> listExperiences(UUID profileId);

    List<ProfileProject> listProjects(UUID profileId);

    List<ProjectTechnology> listProjectTechnologies(UUID profileId);

    ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate);

    boolean deleteProfile(UUID profileId);

    default List<ProfileIdentityCandidate> findIdentityCandidates(ProfileIdentitySearch search) {
        return List.of();
    }

    default Optional<ProfileAggregate> findProfileAggregate(UUID profileId) {
        return findProfileById(profileId)
                .map(profile -> new ProfileAggregate(
                        profile,
                        listContacts(profileId),
                        listLinks(profileId),
                        listSkills(profileId),
                        listLanguages(profileId),
                        listEducation(profileId),
                        listExperiences(profileId),
                        listProjects(profileId),
                        listProjectTechnologies(profileId)
                ));
    }
}

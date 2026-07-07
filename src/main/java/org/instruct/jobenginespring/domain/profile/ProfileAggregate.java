package org.instruct.jobenginespring.domain.profile;

import java.util.List;
import java.util.Objects;

/** Complete normalized profile graph for profile-owned data. */
public record ProfileAggregate(
        UserProfile profile,
        List<ProfileContact> contacts,
        List<ProfileLink> links,
        List<ProfileSkill> skills,
        List<ProfileLanguage> languages,
        List<Education> education,
        List<Experience> experiences,
        List<ProfileProject> projects,
        List<ProjectTechnology> projectTechnologies
) {
    public ProfileAggregate {
        Objects.requireNonNull(profile, "profile must not be null");
        contacts = ProfileRecordSupport.immutableCopy(contacts);
        links = ProfileRecordSupport.immutableCopy(links);
        skills = ProfileRecordSupport.immutableCopy(skills);
        languages = ProfileRecordSupport.immutableCopy(languages);
        education = ProfileRecordSupport.immutableCopy(education);
        experiences = ProfileRecordSupport.immutableCopy(experiences);
        projects = ProfileRecordSupport.immutableCopy(projects);
        projectTechnologies = ProfileRecordSupport.immutableCopy(projectTechnologies);
    }

    public ProfileAggregate(
            UserProfile profile,
            List<ProfileContact> contacts,
            List<ProfileLink> links,
            List<ProfileSkill> skills,
            List<ProfileLanguage> languages,
            List<Education> education,
            List<Experience> experiences,
            List<ProfileProject> projects
    ) {
        this(profile, contacts, links, skills, languages, education, experiences, projects, null);
    }
}

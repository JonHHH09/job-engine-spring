package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.EducationWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ExperienceWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LanguageWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;

import java.util.List;
import java.util.Locale;

final class ProfileWriteCanonicalizer {

    private ProfileWriteCanonicalizer() {
    }

    static ProfileWriteRequest canonicalize(ProfileWriteRequest request) {
        return new ProfileWriteRequest(
                clean(request.fullName()),
                lowerClean(request.email()),
                cleanToNull(request.summary()),
                contacts(request.contacts()),
                links(request.links()),
                skills(request.skills()),
                languages(request.languages()),
                education(request.education()),
                experiences(request.experiences()),
                projects(request.projects())
        );
    }

    private static List<ContactWriteRequest> contacts(List<ContactWriteRequest> contacts) {
        return nullSafe(contacts).stream()
                .map(contact -> new ContactWriteRequest(
                        contact.id(),
                        lowerClean(contact.contactType()),
                        clean(contact.contactValue()),
                        cleanToNull(contact.label())
                ))
                .toList();
    }

    private static List<LinkWriteRequest> links(List<LinkWriteRequest> links) {
        return nullSafe(links).stream()
                .map(link -> new LinkWriteRequest(
                        link.id(),
                        lowerClean(link.linkType()),
                        clean(link.url()),
                        cleanToNull(link.label())
                ))
                .toList();
    }

    private static List<SkillWriteRequest> skills(List<SkillWriteRequest> skills) {
        return nullSafe(skills).stream()
                .map(skill -> new SkillWriteRequest(
                        skill.id(),
                        clean(skill.skill()),
                        lowerClean(skill.normalizedSkill()),
                        cleanToNull(skill.category()),
                        skill.displayOrder()
                ))
                .toList();
    }

    private static List<LanguageWriteRequest> languages(List<LanguageWriteRequest> languages) {
        return nullSafe(languages).stream()
                .map(language -> new LanguageWriteRequest(
                        language.id(),
                        clean(language.language()),
                        lowerClean(language.normalizedLanguage()),
                        cleanToNull(language.proficiency()),
                        language.displayOrder()
                ))
                .toList();
    }

    private static List<EducationWriteRequest> education(List<EducationWriteRequest> education) {
        return nullSafe(education).stream()
                .map(item -> new EducationWriteRequest(
                        item.id(),
                        cleanToNull(item.institution()),
                        cleanToNull(item.degree()),
                        cleanToNull(item.field()),
                        cleanToNull(item.location()),
                        item.startDate(),
                        item.endDate(),
                        cleanToNull(item.relevantFocus())
                ))
                .toList();
    }

    private static List<ExperienceWriteRequest> experiences(List<ExperienceWriteRequest> experiences) {
        return nullSafe(experiences).stream()
                .map(item -> new ExperienceWriteRequest(
                        item.id(),
                        cleanToNull(item.company()),
                        cleanToNull(item.title()),
                        cleanToNull(item.location()),
                        item.startDate(),
                        item.endDate(),
                        cleanToNull(item.description()),
                        item.displayOrder()
                ))
                .toList();
    }

    private static List<ProjectWriteRequest> projects(List<ProjectWriteRequest> projects) {
        return nullSafe(projects).stream()
                .map(project -> new ProjectWriteRequest(
                        project.id(),
                        cleanToNull(project.name()),
                        cleanToNull(project.url()),
                        cleanToNull(project.description()),
                        project.displayOrder(),
                        technologies(project.technologies())
                ))
                .toList();
    }

    private static List<ProjectTechnologyWriteRequest> technologies(List<ProjectTechnologyWriteRequest> technologies) {
        return nullSafe(technologies).stream()
                .map(technology -> new ProjectTechnologyWriteRequest(
                        technology.id(),
                        clean(technology.technology()),
                        lowerClean(technology.normalizedTechnology()),
                        technology.displayOrder()
                ))
                .toList();
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String cleanToNull(String value) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    private static String lowerClean(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}

package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ContactWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.EducationWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ExperienceWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LanguageWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectTechnologyWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProjectWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.SkillWriteRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileWriteValidatorTests {

    @Test
    void acceptsCompleteValidRequest() {
        ProfileWriteValidator.validate(new ProfileWriteRequest(
                "Agentic Dev",
                "agentic@example.com",
                "Builds MCP-native systems",
                List.of(new ContactWriteRequest(null, "Location", "Montreal", "home")),
                List.of(new LinkWriteRequest(null, "Portfolio", "https://example.test", "site")),
                List.of(new SkillWriteRequest(null, "Java", "java", "backend", 0)),
                List.of(new LanguageWriteRequest(null, "English", "english", "fluent", 0)),
                List.of(new EducationWriteRequest(null, "Example University", "BSc", "CS", null,
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-01"), null)),
                List.of(new ExperienceWriteRequest(null, "Example Corp", "Developer", null,
                        LocalDate.parse("2024-01-01"), null, null, 0)),
                List.of(new ProjectWriteRequest(null, "Project", null, null, 0,
                        List.of(new ProjectTechnologyWriteRequest(null, "PostgreSQL", "postgresql", 0))))
        ));
    }

    @Test
    void rejectsBlankTopLevelAndMalformedEmailFields() {
        assertInvalid(null, "request", "must not be null");
        assertInvalid(request(null, "agentic@example.com"), "fullName", "must not be blank");
        assertInvalid(request(" ", "agentic@example.com"), "fullName", "must not be blank");
        assertInvalid(request("Agentic Dev", " "), "email", "must not be blank");
        assertInvalid(request("Agentic Dev", "not-an-email"), "email", "must be a valid email address");
    }

    @Test
    void rejectsNullChildItemsAndBlankChildFields() {
        assertInvalid(requestWithContacts(Collections.singletonList(null)), "contacts[0]", "must not be null");
        assertInvalid(requestWithLinks(List.of(new LinkWriteRequest(null, "portfolio", " ", null))), "links[0].url", "must not be blank");
        assertInvalid(requestWithLanguages(List.of(new LanguageWriteRequest(null, " ", null, null, 0))), "languages[0].language", "must not be blank");
        assertInvalid(requestWithProjects(List.of(new ProjectWriteRequest(null, "Project", null, null, 0,
                Collections.singletonList(null)))), "projects[0].technologies[0]", "must not be null");
    }

    @Test
    void rejectsDuplicateRequestScopedValues() {
        assertInvalid(requestWithContacts(List.of(
                new ContactWriteRequest(null, "Location", "Montreal", null),
                new ContactWriteRequest(null, " location ", "Montreal", null)
        )), "contacts[1].contactValue", "duplicates another contact type/value in this request");
        assertInvalid(requestWithLinks(List.of(
                new LinkWriteRequest(null, "Portfolio", "example.test/profile", null),
                new LinkWriteRequest(null, " portfolio ", "HTTP://EXAMPLE.test/profile/?q=1#bio", null)
        )), "links[1].url", "duplicates another link type/url in this request");
        assertInvalid(requestWithLanguages(List.of(
                new LanguageWriteRequest(null, "English", "english", null, 0),
                new LanguageWriteRequest(null, "Anglais", " english ", null, 1)
        )), "languages[1].normalizedLanguage", "duplicates another normalized language in this request");
        assertInvalid(requestWithEducation(List.of(
                new EducationWriteRequest(null, "Example University", "BSc", "CS", null,
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-01"), null),
                new EducationWriteRequest(null, " example university ", "bsc", " cs ", null,
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-01"), null)
        )), "education[1].institution", "duplicates another education entry in this request");
        assertInvalid(requestWithExperiences(List.of(
                new ExperienceWriteRequest(null, "Example Corp", "Developer", null,
                        LocalDate.parse("2024-01-01"), null, null, 0),
                new ExperienceWriteRequest(null, " example corp ", "developer", null,
                        LocalDate.parse("2024-01-01"), null, null, 1)
        )), "experiences[1].company", "duplicates another experience entry in this request");
        assertInvalid(requestWithProjects(List.of(
                new ProjectWriteRequest(null, "Project", "https://example.test/project", null, 0, null),
                new ProjectWriteRequest(null, " project ", " https://example.test/project ", null, 1, null)
        )), "projects[1].name", "duplicates another project entry in this request");
    }

    @Test
    void rejectsNegativeDisplayOrdersAndInvalidDateRanges() {
        assertInvalid(requestWithSkills(List.of(new SkillWriteRequest(null, "Java", null, "backend", -1))),
                "skills[0].displayOrder", "must be greater than or equal to 0");
        ProfileWriteValidator.validate(requestWithEducation(List.of(new EducationWriteRequest(null, "Example University", null, null, null,
                null, LocalDate.parse("2024-01-01"), null))));
        assertInvalid(requestWithEducation(List.of(new EducationWriteRequest(null, "Example University", null, null, null,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2020-01-01"), null))),
                "education[0].endDate", "must not be before startDate");
        assertInvalid(requestWithProjects(List.of(new ProjectWriteRequest(null, "Project", null, null, -1, null))),
                "projects[0].displayOrder", "must be greater than or equal to 0");
    }

    private static ProfileWriteRequest request(String fullName, String email) {
        return new ProfileWriteRequest(fullName, email, null, null, null, null, null, null, null, null);
    }

    private static ProfileWriteRequest requestWithContacts(List<ContactWriteRequest> contacts) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, contacts, null, null, null, null, null, null);
    }

    private static ProfileWriteRequest requestWithLinks(List<LinkWriteRequest> links) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, links, null, null, null, null, null);
    }

    private static ProfileWriteRequest requestWithSkills(List<SkillWriteRequest> skills) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, skills, null, null, null, null);
    }

    private static ProfileWriteRequest requestWithLanguages(List<LanguageWriteRequest> languages) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, languages, null, null, null);
    }

    private static ProfileWriteRequest requestWithEducation(List<EducationWriteRequest> education) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, null, education, null, null);
    }

    private static ProfileWriteRequest requestWithExperiences(List<ExperienceWriteRequest> experiences) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, null, null, experiences, null);
    }

    private static ProfileWriteRequest requestWithProjects(List<ProjectWriteRequest> projects) {
        return new ProfileWriteRequest("Agentic Dev", "agentic@example.com", null, null, null, null, null, null, null, projects);
    }

    private static void assertInvalid(ProfileWriteRequest request, String field, String reason) {
        ApplicationException exception = assertThrows(ApplicationException.class, () -> ProfileWriteValidator.validate(request));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("Invalid profile write request", exception.safeMessage());
        assertEquals(Map.of("field", field, "reason", reason), exception.details());
    }
}

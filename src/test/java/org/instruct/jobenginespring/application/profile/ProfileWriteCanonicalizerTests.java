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
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfileWriteCanonicalizerTests {

    @Test
    void trimsAndNormalizesProfileWriteRequest() {
        ProfileWriteRequest canonical = ProfileWriteCanonicalizer.canonicalize(new ProfileWriteRequest(
                " Agentic Dev ",
                " AGENTIC@EXAMPLE.COM ",
                " Summary ",
                List.of(new ContactWriteRequest(null, " Phone ", " 555-0100 ", " mobile ")),
                List.of(new LinkWriteRequest(null, " Portfolio ", " https://example.test/me ", " site ")),
                List.of(new SkillWriteRequest(null, " Spring AI ", " Spring AI ", " backend ", 1)),
                List.of(new LanguageWriteRequest(null, " English ", " English ", " fluent ", 2)),
                List.of(new EducationWriteRequest(null, " University ", " BSc ", " CS ", " Remote ",
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2024-01-01"), " Distributed systems ")),
                List.of(new ExperienceWriteRequest(null, " Example Corp ", " Developer ", " Remote ",
                        LocalDate.parse("2024-01-01"), null, " Built services ", 3)),
                List.of(new ProjectWriteRequest(null, " Project ", " https://example.test/project ", " Description ", 4,
                        List.of(new ProjectTechnologyWriteRequest(null, " PostgreSQL ", " PostgreSQL ", 5))))
        ));

        assertEquals("Agentic Dev", canonical.fullName());
        assertEquals("agentic@example.com", canonical.email());
        assertEquals("Summary", canonical.summary());
        assertEquals("phone", canonical.contacts().getFirst().contactType());
        assertEquals("555-0100", canonical.contacts().getFirst().contactValue());
        assertEquals("mobile", canonical.contacts().getFirst().label());
        assertEquals("portfolio", canonical.links().getFirst().linkType());
        assertEquals("https://example.test/me", canonical.links().getFirst().url());
        assertEquals("Spring AI", canonical.skills().getFirst().skill());
        assertEquals("spring ai", canonical.skills().getFirst().normalizedSkill());
        assertEquals("backend", canonical.skills().getFirst().category());
        assertEquals("English", canonical.languages().getFirst().language());
        assertEquals("english", canonical.languages().getFirst().normalizedLanguage());
        assertEquals("fluent", canonical.languages().getFirst().proficiency());
        assertEquals("University", canonical.education().getFirst().institution());
        assertEquals("Built services", canonical.experiences().getFirst().description());
        assertEquals("Project", canonical.projects().getFirst().name());
        assertEquals("postgresql", canonical.projects().getFirst().technologies().getFirst().normalizedTechnology());
    }

    @Test
    void convertsBlankOptionalTextToNullAndNullCollectionsToEmptyLists() {
        ProfileWriteRequest canonical = ProfileWriteCanonicalizer.canonicalize(new ProfileWriteRequest(
                " Agentic Dev ",
                " agentic@example.com ",
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new ProjectWriteRequest(null, " ", " ", " ", 0, null))
        ));

        assertNull(canonical.summary());
        assertEquals(List.of(), canonical.contacts());
        assertEquals(List.of(), canonical.links());
        assertEquals(List.of(), canonical.skills());
        assertNull(canonical.projects().getFirst().name());
        assertNull(canonical.projects().getFirst().url());
        assertEquals(List.of(), canonical.projects().getFirst().technologies());
    }

    @Test
    void persistsCanonicalProfileLinkIdentity() {
        ProfileWriteRequest canonical = ProfileWriteCanonicalizer.canonicalize(new ProfileWriteRequest(
                "Agentic Dev", "agentic@example.com", null, List.of(),
                List.of(new LinkWriteRequest(null, "Portfolio", "HTTP://EXAMPLE.test/me/?q=1#bio", null)),
                List.of(), List.of(), List.of(), List.of(), List.of()
        ));

        assertEquals("https://example.test/me", canonical.links().getFirst().url());
    }
}

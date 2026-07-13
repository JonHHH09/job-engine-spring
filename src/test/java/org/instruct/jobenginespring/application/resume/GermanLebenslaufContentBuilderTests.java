package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufContentBuilderTests {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void buildsEnglishLebenslaufWithoutSummaryAndWithOptionalPersonalDetails() {
        ProfileAggregate profile = profile();
        JobAggregate job = job("Python data transformation audit analytics");
        ProfilePersonalDetails personal = new ProfilePersonalDetails(
                PROFILE_ID,
                LocalDate.of(1998, 1, 15),
                "Canadian",
                null,
                NOW,
                NOW
        );

        StructuredResumeContent content = GermanLebenslaufContentBuilder.buildEnglish(profile, job, personal);

        String rendered = GermanLebenslaufBodyRenderer.render(content);
        assertFalse(rendered.toLowerCase().contains("summary"));
        assertFalse(rendered.toUpperCase().contains("PERSONAL DATA"));
        assertTrue(rendered.contains("Email:"));
        assertTrue(rendered.contains("Date of birth"));
        assertTrue(rendered.contains("PROFESSIONAL EXPERIENCE"));
        assertTrue(rendered.contains("Java Application Developer"));
        assertTrue(content.experiences().getFirst().bullets().size() <= 5);
        assertTrue(content.additional().isEmpty());

        StructuredResumeContent withProjects = GermanLebenslaufContentBuilder.buildEnglish(profile, job, personal, true);
        assertFalse(withProjects.additional().isEmpty());
    }

    @Test
    void offlineTranslatorProducesGermanHeadingsAndKnownPhrases() {
        StructuredResumeContent english = GermanLebenslaufContentBuilder.buildEnglish(profile(), job("Spring Boot banking"), null);
        StructuredResumeContent german = new OfflineGermanResumeTranslator().toGerman(english);
        String rendered = GermanLebenslaufBodyRenderer.render(german);

        assertFalse(rendered.toUpperCase().contains("PERSÖNLICHE DATEN"));
        assertTrue(rendered.contains("BERUFSERFAHRUNG"));
        assertTrue(rendered.contains("AUSBILDUNG"));
        assertTrue(german.language().equals("de"));
    }

    private static ProfileAggregate profile() {
        return new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Joni Hysaj", "root@example.test", "Should not appear as summary section body gate", null, NOW, NOW),
                List.of(new ProfileContact(UUID.randomUUID(), PROFILE_ID, "address", "Montreal, QC, Canada", "Address", NOW, NOW)),
                List.of(new ProfileLink(UUID.randomUUID(), PROFILE_ID, "github", "https://github.com/example", "GitHub", NOW, NOW)),
                List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Python", "python", "Backend", 1, NOW)),
                List.of(new ProfileLanguage(UUID.randomUUID(), PROFILE_ID, "English", "english", "fluent", 0, NOW)),
                List.of(new Education(
                        UUID.randomUUID(), PROFILE_ID, "American University in Bulgaria", "B.A. Computer Science",
                        "Computer Science", "Blagoevgrad, Bulgaria", LocalDate.of(2019, 9, 1), LocalDate.of(2023, 5, 1),
                        "software engineering, data structures", NOW
                )),
                List.of(new Experience(
                        UUID.randomUUID(), PROFILE_ID, "National Bank of Commerce (BKT)", "Java Application Developer",
                        "Tirana, Albania", LocalDate.of(2023, 11, 1), LocalDate.of(2024, 11, 1),
                        "Automated financial processes using Spring Boot and Hibernate in a regulated banking environment. Implemented JWT-based authentication in production-facing systems.",
                        0, NOW
                )),
                List.of(project()),
                List.of()
        );
    }

    private static ProfileProject project() {
        UUID projectId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        return new ProfileProject(
                projectId, PROFILE_ID, "Portfolio", "https://example.test",
                "Built a portfolio website with Next.js and TypeScript.",
                List.of(new ProjectTechnology(UUID.randomUUID(), projectId, "Next.js", "next.js", 0, NOW)),
                0, NOW
        );
    }

    private static JobAggregate job(String description) {
        JobPosting posting = new JobPosting(
                JOB_ID, "text", "EY", "Digitalization Audit", "EY", "Stuttgart",
                description, "CS degree", "Full-time", "Graduate", null,
                "fingerprint-" + description.hashCode(), NOW, NOW
        );
        return new JobAggregate(
                posting,
                List.of(new JobSkill(UUID.randomUUID(), JOB_ID, "Python", "python", true, 0, NOW)),
                null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "label", "hash-" + description.hashCode(), NOW)
        );
    }
}

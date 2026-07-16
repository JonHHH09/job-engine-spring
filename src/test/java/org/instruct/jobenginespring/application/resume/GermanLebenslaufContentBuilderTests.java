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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufContentBuilderTests {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void buildsEnglishLebenslaufWithProfileSummaryAndOptionalPersonalDetails() {
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
        assertEquals("Should not appear as summary section body gate", content.summary());
        assertTrue(rendered.contains("PROFILE"));
        assertTrue(rendered.contains(content.summary()));
        assertFalse(rendered.toUpperCase().contains("PERSONAL DATA"));
        assertTrue(rendered.contains("Email:"));
        assertTrue(rendered.contains("Date of birth"));
        assertTrue(rendered.contains("PROFESSIONAL EXPERIENCE"));
        assertTrue(rendered.contains("Java Application Developer"));
        assertTrue(content.experiences().getFirst().bullets().size() <= 5);
        assertTrue(content.additional().isEmpty());

        StructuredResumeContent withProjects = GermanLebenslaufContentBuilder.buildEnglish(profile, job, personal, true);
        assertFalse(withProjects.additional().isEmpty());
        String withProjectsRendered = GermanLebenslaufBodyRenderer.render(withProjects);
        assertTrue(withProjectsRendered.indexOf("PROJECTS") < withProjectsRendered.indexOf("EDUCATION"));
    }

    @Test
    void filtersZeroOverlapSkillsWhenRelevantSkillsExist() {
        ProfileAggregate base = profile();
        List<ProfileSkill> skills = new ArrayList<>();
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "MLOps", "mlops", "Machine Learning / MLOps", 0, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Model Registry", "model registry", "Machine Learning / MLOps", 1, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Drift Detection", "drift detection", "Machine Learning / MLOps", 2, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Observability", "observability", "Cloud / Ops", 3, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Docker", "docker", "Cloud / Ops", 4, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Enterprise Architecture", "enterprise architecture", "Architecture", 5, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 6, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "IAM", "iam", "Security", 7, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Uncategorized", "uncategorized", null, 8, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Lifecycle Platform", "lifecycle platform", "MLOps", 9, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Kubernetes", "kubernetes", "Ops", 10, NOW));
        skills.add(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Warehousing", "warehousing", "Data", 11, NOW));
        for (int index = 0; index < 30; index++) {
            skills.add(new ProfileSkill(
                    UUID.randomUUID(), PROFILE_ID, "Decorative Skill " + index, "decorative skill " + index,
                    "Systems / Tools", index + 12, NOW
            ));
        }
        ProfileAggregate expanded = new ProfileAggregate(
                base.profile(), base.contacts(), base.links(), skills, base.languages(), base.education(),
                base.experiences(), base.projects(), base.projectTechnologies()
        );

        StructuredResumeContent content = GermanLebenslaufContentBuilder.buildEnglish(
                expanded,
                job("MLOps model registry drift detection observability Docker enterprise architecture"),
                null,
                true
        );

        List<String> selected = content.skillGroups().stream().flatMap(group -> group.skills().stream()).toList();
        assertEquals(6, selected.size());
        assertEquals("Machine Learning / MLOps", content.skillGroups().getFirst().category());
        assertTrue(selected.containsAll(List.of("MLOps", "Model Registry", "Drift Detection", "Observability", "Docker")));
        assertTrue(selected.stream().noneMatch(skill -> skill.startsWith("Decorative Skill")));
    }

    @Test
    void capsRelevantSkillsAndFallsBackDeterministicallyWhenNoneOverlap() {
        ProfileAggregate base = profile();
        List<ProfileSkill> relevant = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new ProfileSkill(
                        UUID.randomUUID(), PROFILE_ID, "MLOps Tool " + index, "mlops tool " + index,
                        "Machine Learning / MLOps", index, NOW
                ))
                .toList();
        ProfileAggregate expanded = new ProfileAggregate(
                base.profile(), base.contacts(), base.links(), relevant, base.languages(), base.education(),
                base.experiences(), base.projects(), base.projectTechnologies()
        );

        StructuredResumeContent capped = GermanLebenslaufContentBuilder.buildEnglish(expanded, job("MLOps"), null);
        List<String> cappedSkills = capped.skillGroups().stream().flatMap(group -> group.skills().stream()).toList();
        assertEquals(24, cappedSkills.size());
        assertFalse(cappedSkills.contains("MLOps Tool 29"));

        ProfileAggregate noOverlap = new ProfileAggregate(
                base.profile(), base.contacts(), base.links(),
                List.of(
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Kotlin", "kotlin", null, 1, NOW)
                ),
                base.languages(), base.education(), base.experiences(), base.projects(), base.projectTechnologies()
        );
        StructuredResumeContent fallback = GermanLebenslaufContentBuilder.buildEnglish(noOverlap, job("COBOL mainframe"), null);
        assertEquals(List.of("Java", "Kotlin"),
                fallback.skillGroups().stream().flatMap(group -> group.skills().stream()).toList());
    }

    @Test
    void offlineTranslatorProducesGermanHeadingsAndKnownPhrases() {
        StructuredResumeContent english = GermanLebenslaufContentBuilder.buildEnglish(profile(), job("Spring Boot banking"), null);
        StructuredResumeContent german = new OfflineGermanResumeTranslator().toGerman(english);
        String rendered = GermanLebenslaufBodyRenderer.render(german);

        assertFalse(rendered.toUpperCase().contains("PERSÖNLICHE DATEN"));
        assertTrue(rendered.contains("PROFIL"));
        assertTrue(german.summary() != null);
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

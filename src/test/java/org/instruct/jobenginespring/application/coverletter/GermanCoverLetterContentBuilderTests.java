package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanCoverLetterContentBuilderTests {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void buildsGermanStructuredContentFromOnlyNormalizedEvidence() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Candidate", "candidate@example.test",
                        "Backend delivery and reliable software systems", null, NOW, NOW),
                List.of(new ProfileContact(UUID.randomUUID(), PROFILE_ID, "phone", "+49 555 0100", "Telefon", NOW, NOW)),
                List.of(new ProfileLink(UUID.randomUUID(), PROFILE_ID, "github", "https://github.example.test/synthetic", "GitHub", NOW, NOW)),
                List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "PostgreSQL", "postgresql", "Data", 1, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Unrelated", "unrelated", "Other", 2, NOW)),
                List.of(), List.of(),
                List.of(new Experience(UUID.randomUUID(), PROFILE_ID, "Java Developer", "Example Systems", "Berlin",
                        LocalDate.of(2024, 1, 1), null, "Built Java services.", 0, NOW)),
                List.of()
        );
        JobAggregate job = job("Backend Engineer", "Example GmbH", List.of("Java", "PostgreSQL"));

        GermanCoverLetterContent content = new GermanCoverLetterContentBuilder().build(profile, job);

        assertEquals("Synthetic Candidate", content.senderName());
        assertEquals("candidate@example.test", content.personalFields().getFirst().value());
        assertEquals("https://github.example.test/synthetic", content.personalFields().get(2).value());
        assertEquals("Bewerbung als Backend Engineer bei Example GmbH", content.subject());
        assertEquals("Sehr geehrte Damen und Herren,", content.salutation());
        assertEquals("Synthetic Candidate", content.signature());
        assertTrue(content.paragraphs().size() >= 3);
        String combined = String.join(" ", content.paragraphs());
        assertTrue(combined.contains("Example Systems"));
        assertTrue(combined.contains("Java"));
        assertTrue(combined.contains("PostgreSQL"));
        assertFalse(combined.contains("Unrelated"));
        GermanCoverLetterContentReview.review(content);
    }

    @Test
    void usesEducationEvidenceWhenExperienceIsAbsent() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Graduate", "graduate@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Python", "python", "Data", 0, NOW)),
                List.of(),
                List.of(new Education(UUID.randomUUID(), PROFILE_ID, "Example University", "B.Sc. Computer Science", null, "Leipzig",
                        LocalDate.of(2022, 1, 1), LocalDate.of(2026, 1, 1), "Distributed systems", NOW)),
                List.of(), List.of()
        );

        GermanCoverLetterContent content = new GermanCoverLetterContentBuilder().build(profile, job("Data Engineer", "Data Haus", List.of("Python")));

        assertTrue(String.join(" ", content.paragraphs()).contains("Example University"));
        GermanCoverLetterContentReview.review(content);
    }

    private static JobAggregate job(String title, String company, List<String> skills) {
        UUID id = JOB_ID;
        JobPosting posting = new JobPosting(id, "text", "Synthetic source", title, company, "Berlin",
                "Build reliable services", null, null, "mid-level", null, "synthetic-fingerprint-" + title,
                NOW, NOW);
        return new JobAggregate(posting,
                skills.stream().map(skill -> new JobSkill(UUID.randomUUID(), id, skill, skill.toLowerCase(), true, 0, NOW)).toList(),
                null,
                new JobTextIngestion(UUID.randomUUID(), id, "Synthetic source", "hash-" + title, NOW));
    }
}

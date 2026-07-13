package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
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

class GermanLebenslaufContentBuilderEdgeTests {

    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void coversSparseContactsEducationAndEmptyDescriptionBullets() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Name", "n@example.test", null, null, NOW, NOW),
                List.of(
                        new ProfileContact(UUID.randomUUID(), PROFILE_ID, "email", "n@example.test", "Email", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), PROFILE_ID, "other", "value", "", NOW, NOW)
                ),
                List.of(),
                List.of(new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Java", "java", "Backend", 0, NOW)),
                List.of(new ProfileLanguage(UUID.randomUUID(), PROFILE_ID, "English", "english", null, 0, NOW)),
                List.of(new Education(
                        UUID.randomUUID(), PROFILE_ID, "Uni", "Degree", "Field", "Loc",
                        LocalDate.of(2019, 1, 1), LocalDate.of(2023, 1, 1), "Focus", NOW
                )),
                List.of(
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Co", "Role", "City",
                                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), "   \n   ", 0, NOW),
                        new Experience(UUID.randomUUID(), PROFILE_ID, "Co2", "Role2", null,
                                LocalDate.of(2022, 1, 1), null, null, 1, NOW)
                ),
                List.of(new ProfileProject(UUID.randomUUID(), PROFILE_ID, "P", null, null, List.of(), 0, NOW)),
                List.of()
        );
        JobAggregate job = new JobAggregate(
                new JobPosting(JOB_ID, "text", "L", "ab", "xy", "Loc", "zz", null, null, null, null, "fp-edge", NOW, NOW),
                List.of(),
                null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "l", "hash-edge", NOW)
        );

        StructuredResumeContent content = GermanLebenslaufContentBuilder.buildEnglish(profile, job, null);
        assertFalse(content.experiences().isEmpty());
        assertTrue(content.education().stream().anyMatch(e -> e.bullets().stream().anyMatch(b -> b.contains("Field"))));
        assertTrue(content.education().stream().anyMatch(e -> e.bullets().stream().anyMatch(b -> b.contains("International"))));
    }

    @Test
    void coversPersonalDetailsDateAndNationalityBranches() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Name", "n@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        JobAggregate job = new JobAggregate(
                new JobPosting(JOB_ID, "text", "L", "Title", "Company", "Loc", "description text", null, null, null, null, "fp-edge2", NOW, NOW),
                List.of(), null, new JobTextIngestion(UUID.randomUUID(), JOB_ID, "l", "hash-edge2", NOW)
        );
        StructuredResumeContent withDob = GermanLebenslaufContentBuilder.buildEnglish(
                profile, job, new ProfilePersonalDetails(PROFILE_ID, LocalDate.of(1990, 1, 1), null, null, NOW, NOW)
        );
        assertTrue(withDob.personalFields().stream().anyMatch(f -> f.label().equals("Date of birth")));
        StructuredResumeContent withNationality = GermanLebenslaufContentBuilder.buildEnglish(
                profile, job, new ProfilePersonalDetails(PROFILE_ID, null, "Canadian", null, NOW, NOW)
        );
        assertTrue(withNationality.personalFields().stream().anyMatch(f -> f.label().equals("Nationality")));
    }

    @Test
    void coversHelperBranchesDirectly() {
        assertFalse(GermanLebenslaufContentBuilder.hasText(null));
        assertFalse(GermanLebenslaufContentBuilder.hasText("  "));
        assertTrue(GermanLebenslaufContentBuilder.hasText("x"));
        assertFalse(GermanLebenslaufContentBuilder.containsEmailSignal(null));
        assertFalse(GermanLebenslaufContentBuilder.containsEmailSignal("  "));
        assertTrue(GermanLebenslaufContentBuilder.containsEmailSignal("Email"));
        assertTrue(GermanLebenslaufContentBuilder.containsEmailSignal("a@b.c"));
        assertFalse(GermanLebenslaufContentBuilder.containsEmailSignal("phone"));
        assertEquals("one", GermanLebenslaufContentBuilder.stripBulletPrefix("- one"));
        assertEquals("two", GermanLebenslaufContentBuilder.stripBulletPrefix("* two"));
        assertEquals("three", GermanLebenslaufContentBuilder.stripBulletPrefix("• three"));
        assertEquals("four", GermanLebenslaufContentBuilder.stripBulletPrefix("1) four"));
        assertEquals("plain", GermanLebenslaufContentBuilder.stripBulletPrefix("plain"));
        ProfileContact emailType = new ProfileContact(UUID.randomUUID(), PROFILE_ID, "email", "x@y.z", null, NOW, NOW);
        ProfileContact emailTypeOnly = new ProfileContact(UUID.randomUUID(), PROFILE_ID, "email", "not-an-address", null, NOW, NOW);
        ProfileContact phone = new ProfileContact(UUID.randomUUID(), PROFILE_ID, "phone", "123", "Mobile", NOW, NOW);
        assertTrue(GermanLebenslaufContentBuilder.isEmailLike(emailType));
        assertTrue(GermanLebenslaufContentBuilder.isEmailLike(emailTypeOnly));
        assertFalse(GermanLebenslaufContentBuilder.isEmailLike(phone));
    }

    @Test
    void includesProjectsWithoutDescriptionOrTechnologiesWhenRequested() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Name", "n@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ProfileProject(UUID.randomUUID(), PROFILE_ID, "Bare Project", null, null, List.of(), 0, NOW)),
                List.of()
        );
        JobAggregate job = new JobAggregate(
                new JobPosting(JOB_ID, "text", "L", "Title", "Company", "Loc", "description text", null, null, null, null, "fp-proj", NOW, NOW),
                List.of(), null, new JobTextIngestion(UUID.randomUUID(), JOB_ID, "l", "hash-proj", NOW)
        );
        StructuredResumeContent content = GermanLebenslaufContentBuilder.buildEnglish(profile, job, null, true);
        assertEquals(1, content.additional().size());
        assertTrue(content.additional().getFirst().bullets().isEmpty());
    }
}

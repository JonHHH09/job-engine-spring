package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanCoverLetterCoverageEdgeTests {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void buildsWithoutCompanyEvidenceOrMatchedSkillsAndBoundsContacts() {
        List<ProfileContact> contacts = List.of(
                contact("email", "not-an-address", null),
                contact("other", "other@example.test", "Other"),
                contact("phone", "+49 555 0100", " "),
                contact("signal", "synthetic-signal", "Signal"),
                contact("telegram", "synthetic-telegram", "Telegram"),
                contact("website", "synthetic-site", "Website")
        );
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Candidate", "candidate@example.test", null, null, NOW, NOW),
                contacts,
                List.of(new ProfileLink(UUID.randomUUID(), PROFILE_ID, "github",
                        "https://example.test/synthetic", null, NOW, NOW)),
                List.of(
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "Rust", "rust", "Backend", 1, NOW),
                        new ProfileSkill(UUID.randomUUID(), PROFILE_ID, "C", "c", "Backend", 0, NOW)
                ),
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        JobAggregate job = new JobAggregate(
                new JobPosting(JOB_ID, "text", "Synthetic", "X", null, null, ".",
                        null, null, null, null, "edge-fingerprint", NOW, NOW),
                List.of(), null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "Synthetic", "edge-hash", NOW)
        );

        GermanCoverLetterContent content = new GermanCoverLetterContentBuilder().build(profile, job);

        assertEquals("Bewerbung als X", content.subject());
        assertEquals(6, content.personalFields().size());
        assertEquals("phone", content.personalFields().stream()
                .filter(field -> field.value().equals("+49 555 0100")).findFirst().orElseThrow().label());
        assertTrue(String.join(" ", content.paragraphs()).contains("Rust"));
        assertFalse(String.join(" ", content.paragraphs()).contains("Example"));
    }

    @Test
    void ordersExperienceUsingEndStartAndMinimumDateFallbacks() {
        ProfileAggregate profile = new ProfileAggregate(
                new UserProfile(PROFILE_ID, "Synthetic Candidate", "candidate@example.test", null, null, NOW, NOW),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(
                        experience("No Dates", null, null),
                        experience("Current Role", LocalDate.of(2025, 1, 1), null),
                        experience("Ended Role", LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1))
                ),
                List.of(), List.of()
        );
        JobAggregate job = new JobAggregate(
                new JobPosting(JOB_ID, "text", "Synthetic", "Engineer", "Example GmbH", null,
                        "Reliable systems", null, null, null, null, "experience-fingerprint", NOW, NOW),
                List.of(), null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "Synthetic", "experience-hash", NOW)
        );

        GermanCoverLetterContent content = new GermanCoverLetterContentBuilder().build(profile, job);

        assertTrue(String.join(" ", content.paragraphs()).contains("Current Role"));
    }

    @Test
    void validatesContentNormalizationBodyProjectionAndReviewEdges() {
        GermanCoverLetterContent normalized = new GermanCoverLetterContent(
                " Synthetic Candidate ", null, " ", " ", " Engineer ",
                " Bewerbung als Engineer ", " Sehr geehrte Damen und Herren, ",
                List.of(" Erster ausführlicher Absatz mit belastbarer Berufserfahrung. ",
                        " Zweiter ausführlicher Absatz über die Zusammenarbeit im Team. "),
                " Mit freundlichen Grüßen, ", " Synthetic Candidate "
        );
        assertTrue(normalized.personalFields().isEmpty());
        assertEquals("Synthetic Candidate", normalized.senderName());
        String body = GermanCoverLetterBodyRenderer.render(normalized);
        assertFalse(body.contains("null"));
        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class,
                () -> GermanCoverLetterContentReview.review(normalized));
        assertThrows(NullPointerException.class, () -> GermanCoverLetterBodyRenderer.render(null));
        assertThrows(NullPointerException.class, () -> GermanCoverLetterContentReview.review(null));

        GermanCoverLetterContent shortInvalid = new GermanCoverLetterContent(
                "A", List.of(new GermanCoverLetterContent.PersonalField("Mail", "candidate")),
                null, null, "Backend Engineer", "Application", "Hallo,",
                List.of("Eins", "Zwei"), "Gruß", "A"
        );
        assertThrows(org.instruct.jobenginespring.application.error.ApplicationException.class,
                () -> GermanCoverLetterContentReview.review(shortInvalid));

        assertThrows(NullPointerException.class, () -> new GermanCoverLetterContent(
                "Candidate", List.of(), null, null, "Engineer", "Bewerbung als Engineer", "Hallo",
                null, "Gruß", "Candidate"
        ));
        assertThrows(IllegalArgumentException.class,
                () -> new GermanCoverLetterContent.PersonalField(" ", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new GermanCoverLetterContent.PersonalField("label", " "));
    }

    private static ProfileContact contact(String type, String value, String label) {
        return new ProfileContact(UUID.randomUUID(), PROFILE_ID, type, value, label, NOW, NOW);
    }

    private static Experience experience(String title, LocalDate start, LocalDate end) {
        return new Experience(UUID.randomUUID(), PROFILE_ID, title, "Example Systems", null,
                start, end, "Synthetic evidence", 0, NOW);
    }
}

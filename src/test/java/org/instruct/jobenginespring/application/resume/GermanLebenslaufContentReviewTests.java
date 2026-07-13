package org.instruct.jobenginespring.application.resume;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GermanLebenslaufContentReviewTests {

    @Test
    void acceptsValidContent() {
        assertDoesNotThrow(() -> GermanLebenslaufContentReview.review(valid()));
    }

    @Test
    void rejectsMissingEmailAndMissingExperienceEducation() {
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Phone", "1")),
                        List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", null, null, null, List.of("ok"))),
                        List.of(), List.of(), List.of(), List.of())
        ));
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                        List.of(), List.of(), List.of(), List.of(), List.of())
        ));
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                        List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", null, null, null, List.of("ok"))),
                        List.of(new StructuredResumeContent.EducationEntry("", "Uni", null, null, null, List.of())),
                        List.of(), List.of(), List.of())
        ));
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                        List.of(new StructuredResumeContent.ExperienceEntry("", "Co", null, null, null, List.of("ok"))),
                        List.of(), List.of(), List.of(), List.of())
        ));
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                        List.of(new StructuredResumeContent.ExperienceEntry("Dev", "", null, null, null, List.of("ok"))),
                        List.of(), List.of(), List.of(), List.of())
        ));
        assertThrows(ApplicationException.class, () -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                        List.of(),
                        List.of(new StructuredResumeContent.EducationEntry("Degree", "", null, null, null, List.of())),
                        List.of(), List.of(), List.of())
        ));
        // label email path without @ still accepted via label contains email
        assertDoesNotThrow(() -> GermanLebenslaufContentReview.review(
                new StructuredResumeContent("Name", "en", List.of(new StructuredResumeContent.PersonalField("Email", "not-an-address")),
                        List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", null, LocalDate.of(2020,1,1), null, List.of("Worked hard on delivery"))),
                        List.of(), List.of(), List.of(), List.of())
        ));
    }

    @Test
    void acceptsEMailLabelWithAtSymbol() {
        StructuredResumeContent germanEmail = new StructuredResumeContent(
                "Name", "de",
                List.of(new StructuredResumeContent.PersonalField("E-Mail", "user@example.test")),
                List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", null, LocalDate.of(2020, 1, 1), null, List.of("Work"))),
                List.of(), List.of(), List.of(), List.of()
        );
        assertDoesNotThrow(() -> GermanLebenslaufContentReview.review(germanEmail));
        assertTrue(GermanLebenslaufBodyRenderer.render(germanEmail).contains("E-Mail"));
    }

    private static StructuredResumeContent valid() {
        return new StructuredResumeContent(
                "Name", "en",
                List.of(new StructuredResumeContent.PersonalField("Email", "a@b.c")),
                List.of(new StructuredResumeContent.ExperienceEntry("Dev", "Co", "City", LocalDate.of(2023, 1, 1), null, List.of("Built APIs"))),
                List.of(new StructuredResumeContent.EducationEntry("B.A.", "Uni", null, null, null, List.of("CS"))),
                List.of(), List.of(), List.of()
        );
    }
}

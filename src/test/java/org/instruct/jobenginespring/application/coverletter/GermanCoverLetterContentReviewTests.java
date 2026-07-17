package org.instruct.jobenginespring.application.coverletter;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GermanCoverLetterContentReviewTests {

    @Test
    void rejectsMissingEmailAndShortStructure() {
        GermanCoverLetterContent content = new GermanCoverLetterContent(
                "Synthetic Candidate",
                List.of(new GermanCoverLetterContent.PersonalField("Telefon", "+49 555 0100")),
                "Example GmbH",
                "Berlin",
                "Backend Engineer",
                "Bewerbung als Backend Engineer",
                "Sehr geehrte Damen und Herren,",
                List.of("Kurz"),
                "Mit freundlichen Grüßen,",
                "Synthetic Candidate"
        );

        assertThrows(ApplicationException.class, () -> GermanCoverLetterContentReview.review(content));
    }

    @Test
    void rejectsControlCharactersBeforePdfRendering() {
        GermanCoverLetterContent content = new GermanCoverLetterContent(
                "Synthetic Candidate",
                List.of(new GermanCoverLetterContent.PersonalField("E-Mail", "candidate@example.test")),
                "Example GmbH",
                "Berlin",
                "Backend Engineer",
                "Bewerbung als Backend Engineer",
                "Sehr geehrte Damen und Herren,",
                List.of("Eine belastbare Erfahrung\nmit Backend-Systemen.", "Java und PostgreSQL."),
                "Mit freundlichen Grüßen,",
                "Synthetic Candidate"
        );

        assertThrows(ApplicationException.class, () -> GermanCoverLetterContentReview.review(content));
    }
}

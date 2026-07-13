package org.instruct.jobenginespring.application.resume;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredResumeContentValidationTests {

    @Test
    void rejectsBlankFullNameLanguagePersonalFieldAndSkillGroup() {
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent(
                "  ", "en", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        assertThrows(NullPointerException.class, () -> new StructuredResumeContent(
                "Name", null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.PersonalField(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.PersonalField("Email", " "));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.SkillGroup(" ", List.of("Java")));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.SkillGroup("Backend", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StructuredResumeContent.SkillGroup("Backend", List.of("  ")));
        StructuredResumeContent ok = new StructuredResumeContent(
                "Name", " EN ", null, null, null, null, null, null
        );
        assertTrue(ok.experiences().isEmpty());
        assertTrue(ok.language().equals("en"));
    }
}

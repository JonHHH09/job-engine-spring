package org.instruct.jobenginespring.adapter.out.extraction;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor.ProfileTextExtractionInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicProfileTextExtractorTests {

    private final DeterministicProfileTextExtractor extractor = new DeterministicProfileTextExtractor();

    @Test
    void extractsSectionAwareProfileFieldsWithoutTreatingLanguagesAsSkills() {
        ProfileWriteRequest request = extractor.extractProfile(new ProfileTextExtractionInput("""
                Agentic Dev
                agentic@example.test | +1 438 555 0100
                https://linkedin.com/in/agentic-dev?trk=public
                https://github.com/agentic-dev/

                Summary
                Builds MCP-native Spring systems with deterministic ingestion.

                Technical Skills
                Java, Spring Boot, PostgreSQL, Docker, Testcontainers, TypeScript

                Languages
                English - Fluent
                French - Beginner
                """, "resume.pdf"));

        assertEquals("Agentic Dev", request.fullName());
        assertEquals("agentic@example.test", request.email());
        assertEquals("Builds MCP-native Spring systems with deterministic ingestion.", request.summary());
        assertEquals(List.of("email", "phone"), request.contacts().stream().map(contact -> contact.contactType()).toList());
        assertEquals(List.of("linkedin", "github"), request.links().stream().map(link -> link.linkType()).toList());
        assertEquals("https://linkedin.com/in/agentic-dev", request.links().getFirst().url());
        assertEquals("https://github.com/agentic-dev", request.links().get(1).url());
        assertEquals(List.of("spring boot", "testcontainers", "typescript", "postgresql", "docker", "java"),
                request.skills().stream().map(skill -> skill.normalizedSkill()).toList());
        assertEquals(List.of("english", "french"), request.languages().stream()
                .map(language -> language.normalizedLanguage())
                .toList());
    }

    @Test
    void ignoresSkillsMentionedOutsideRecognizedSkillSections() {
        ProfileWriteRequest request = extractor.extractProfile(new ProfileTextExtractionInput("""
                Agentic Dev
                agentic@example.test

                Experience
                Built Java and Docker services.

                Languages
                English
                """, "resume.pdf"));

        assertTrue(request.skills().isEmpty());
        assertEquals(List.of("english"), request.languages().stream()
                .map(language -> language.normalizedLanguage())
                .toList());
    }

    @Test
    void rejectsBlankInputSafely() {
        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> extractor.extractProfile(new ProfileTextExtractionInput(" ", "resume.pdf")));

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("text", exception.details().get("field"));
    }
}

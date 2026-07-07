package org.instruct.jobenginespring.adapter.out.extraction;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor.ProfileTextExtractionInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
    void extractsStructuredExperienceEducationAndProjectsFromSections() {
        ProfileWriteRequest request = extractor.extractProfile(new ProfileTextExtractionInput("""
                Agentic Dev
                agentic@example.test

                Summary
                Builds deterministic systems.

                Technical Skills
                Java, Spring Boot, PostgreSQL, Docker

                Experience
                Senior Software Engineer | Example Bank | Montreal, QC | Jan 2024 - Present
                - Built Spring Boot services for regulated workflows.
                - Improved deterministic profile ingestion.

                Software Developer | Product Studio | Remote | 2021 - 2023
                Delivered Java and PostgreSQL systems.

                Education
                American University in Bulgaria | B.A. Computer Science | Blagoevgrad, Bulgaria | 2019 - 2023
                Focused on distributed systems and software engineering.

                Projects
                Job Engine Spring | https://example.test/job-engine | Java, Spring Boot, PostgreSQL
                MCP-native resume ingestion and PDF generation backend.
                """, "resume.pdf"));

        assertEquals(2, request.experiences().size());
        assertEquals("Senior Software Engineer", request.experiences().getFirst().title());
        assertEquals("Example Bank", request.experiences().getFirst().company());
        assertEquals("Montreal, QC", request.experiences().getFirst().location());
        assertEquals(LocalDate.parse("2024-01-01"), request.experiences().getFirst().startDate());
        assertEquals(null, request.experiences().getFirst().endDate());
        assertEquals("Built Spring Boot services for regulated workflows.\nImproved deterministic profile ingestion.",
                request.experiences().getFirst().description());
        assertEquals("Software Developer", request.experiences().get(1).title());
        assertEquals(LocalDate.parse("2021-01-01"), request.experiences().get(1).startDate());
        assertEquals(LocalDate.parse("2023-01-01"), request.experiences().get(1).endDate());

        assertEquals(1, request.education().size());
        assertEquals("American University in Bulgaria", request.education().getFirst().institution());
        assertEquals("B.A.", request.education().getFirst().degree());
        assertEquals("Computer Science", request.education().getFirst().field());
        assertEquals("Blagoevgrad, Bulgaria", request.education().getFirst().location());
        assertEquals(LocalDate.parse("2019-01-01"), request.education().getFirst().startDate());
        assertEquals(LocalDate.parse("2023-01-01"), request.education().getFirst().endDate());

        assertEquals(1, request.projects().size());
        assertEquals("Job Engine Spring", request.projects().getFirst().name());
        assertEquals("https://example.test/job-engine", request.projects().getFirst().url());
        assertEquals("MCP-native resume ingestion and PDF generation backend.", request.projects().getFirst().description());
        assertEquals(List.of("spring boot", "postgresql", "java", "mcp"), request.projects().getFirst().technologies().stream()
                .map(technology -> technology.normalizedTechnology())
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

package org.instruct.jobenginespring.adapter.out.extraction;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor.ProfileTextExtractionInput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicProfileTextExtractorEdgeTests {

    private final DeterministicProfileTextExtractor extractor = new DeterministicProfileTextExtractor();

    @Test
    void extractionCoversFallbackIdentityOptionalContactAndFallbackSummary() {
        assertThrows(ApplicationException.class, () -> extractor.extractProfile(null));
        assertThrows(ApplicationException.class, () ->
                extractor.extractProfile(new ProfileTextExtractionInput(null, "resume.pdf")));

        var fallbackName = extractor.extractProfile(new ProfileTextExtractionInput(
                "fallback.name@example.test", "resume.pdf"));
        assertEquals("Fallback Name", fallbackName.fullName());
        assertNull(fallbackName.summary());

        var fallbackSummary = extractor.extractProfile(new ProfileTextExtractionInput("""
                Explicit Name
                explicit@example.test
                +1 438 555 0100
                https://linkedin.com/in/fallback
                https://github.com/fallback
                Fallback profile summary
                """, "resume.pdf"));
        assertEquals("Fallback profile summary", fallbackSummary.summary());

        ApplicationException missingEmail = assertThrows(ApplicationException.class, () ->
                extractor.extractProfile(new ProfileTextExtractionInput("Only A Name", "resume.pdf")));
        assertEquals("email", missingEmail.details().get("field"));

        ApplicationException missingName = assertThrows(ApplicationException.class, () ->
                extractor.extractProfile(new ProfileTextExtractionInput("single@example.test", "resume.pdf")));
        assertEquals("fullName", missingName.details().get("field"));

        var structured = extractor.extractProfile(new ProfileTextExtractionInput("""
                Structured Person
                structured@example.test
                Experience
                Engineer | First Company
                Jan 2020 - Jan 2022
                Senior Engineer | Second Company
                Jan 2022 - Present
                Projects
                Project One | https://example.test/one
                Project Two - https://example.test/two
                """, "resume.pdf"));
        assertEquals(4, structured.experiences().size());
        assertEquals(2, structured.projects().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pureExtractionHelpersCoverAllAcceptedShapesAndFallbacks() throws Exception {
        invoke("firstLikelyName", new Class<?>[]{String.class, String.class},
                "\nname@example.test\nhttps://example.test\n" + "X".repeat(81) + "\n123\nValid Name", "name@example.test");

        List<LinkWriteRequest> links = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        invoke("addLink", new Class<?>[]{List.class, java.util.Set.class, String.class, String.class, String.class}, links, seen, "website", null, "Website");
        invoke("addLink", new Class<?>[]{List.class, java.util.Set.class, String.class, String.class, String.class}, links, seen, "website", "example.test/", "Website");
        invoke("addLink", new Class<?>[]{List.class, java.util.Set.class, String.class, String.class, String.class}, links, seen, "website", "example.test/", "Website");
        assertEquals(1, links.size());

        for (String value : List.of("summary", "profile", "professional summary", "other")) {
            invoke("isSummarySection", new Class<?>[]{String.class}, value);
        }
        for (String value : List.of("skills", "technical skills", "technologies", "technology stack", "other")) {
            invoke("isSkillSection", new Class<?>[]{String.class}, value);
        }
        for (String value : List.of("languages", "language skills", "other")) {
            invoke("isLanguageSection", new Class<?>[]{String.class}, value);
        }
        for (String value : List.of("experience", "work experience", "other")) {
            invoke("isExperienceSection", new Class<?>[]{String.class}, value);
        }
        for (String line : List.of("Jan 2020 - Present", "Name | Company", "Name - Company", "plain")) {
            invoke("startsStructuredEntry", new Class<?>[]{String.class}, line);
        }

        List<List<String>> entries = List.of(
                List.of(),
                List.of(""),
                List.of("Role"),
                List.of("Role | Company"),
                List.of("Role | Company | Remote", "", "- Built systems")
        );
        invoke("experiences", new Class<?>[]{List.class}, entries);
        invoke("education", new Class<?>[]{List.class}, entries);
        invoke("projects", new Class<?>[]{List.class}, entries);

        List<String> ignoredEntry = new ArrayList<>(List.of("Not a structured section"));
        invoke("flushEntry", new Class<?>[]{String.class, List.class, List.class, List.class, List.class},
                "other", ignoredEntry, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertEquals(List.of(), ignoredEntry);

        for (String end : List.of("present", "current", "2024")) {
            invoke("parseEndDate", new Class<?>[]{String.class}, end);
        }
        for (String month : List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec", "unknown")) {
            invoke("month", new Class<?>[]{String.class}, month);
        }
        invoke("degreeField", new Class<?>[]{String.class}, new Object[]{null});
        invoke("degreeField", new Class<?>[]{String.class}, " ");
        invoke("degreeField", new Class<?>[]{String.class}, "Certificate");
        invoke("degreeField", new Class<?>[]{String.class}, "Master in Computer Science");

        invoke("summary", new Class<?>[]{String.class, String.class}, "Name\nsecond line", "\n");
        invoke("summary", new Class<?>[]{String.class, String.class}, "Name\nsecond line", "Summary line\nOther");
        invoke("truncate", new Class<?>[]{String.class}, new Object[]{null});
        invoke("truncate", new Class<?>[]{String.class}, "X".repeat(501));
        invoke("normalizeUrl", new Class<?>[]{String.class}, "example.test/path/?query=secret");
        invoke("normalizeUrl", new Class<?>[]{String.class}, "http://example.test/");
        invoke("normalizeUrl", new Class<?>[]{String.class}, "https://example.test/");

        for (String display : List.of(
                "java", "spring boot", "spring cloud", "spring ai", "kotlin", "kotlin multiplatform", "python",
                "postgresql", "flyway", "jdbc", "mcp", "docker", "testcontainers", "react", "next.js",
                "typescript", "javascript", "qt", "qml", "pyside6", "sqlite", "aws", "custom skill"
        )) {
            invoke("displayName", new Class<?>[]{String.class}, display);
        }
        assertEquals("", invoke("titleCase", new Class<?>[]{String.class}, " "));
        assertEquals("A Bc", invoke("titleCase", new Class<?>[]{String.class}, "a BC"));

        // Invalid empty entries are ignored rather than materialized.
        assertEquals(4, ((List<?>) invoke("experiences", new Class<?>[]{List.class}, entries)).size());
        assertNull(invoke("truncate", new Class<?>[]{String.class}, new Object[]{null}));
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = DeterministicProfileTextExtractor.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}

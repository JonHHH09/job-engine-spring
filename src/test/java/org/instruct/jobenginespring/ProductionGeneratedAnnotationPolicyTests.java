package org.instruct.jobenginespring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionGeneratedAnnotationPolicyTests {

    @Test
    void productionSourcesDoNotUseGeneratedAnnotationsToHideHandWrittenCode() throws IOException {
        Path productionSources = Path.of("src", "main", "java");

        List<String> violations;
        try (var paths = Files.walk(productionSources)) {
            violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(ProductionGeneratedAnnotationPolicyTests::containsGeneratedAnnotation)
                    .map(productionSources::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertEquals(List.of(), violations,
                "Generated annotations require a narrowly justified policy exception; do not use them to hide hand-written production code from JaCoCo");
    }

    private static boolean containsGeneratedAnnotation(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("@lombok.Generated")
                    || source.contains("@Generated")
                    || source.contains("import lombok.Generated");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect production source " + path, exception);
        }
    }
}

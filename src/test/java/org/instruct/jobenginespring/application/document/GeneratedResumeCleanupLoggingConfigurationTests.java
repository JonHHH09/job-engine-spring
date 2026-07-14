package org.instruct.jobenginespring.application.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedResumeCleanupLoggingConfigurationTests {

    @Test
    void cleanupDiagnosticsUseStderrAndNeverStdout() throws Exception {
        try (var stream = getClass().getResourceAsStream("/logback-spring.xml")) {
            var configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(configuration.contains("<target>System.err</target>"));
            assertTrue(configuration.contains(GeneratedResumeCleanupExecutor.class.getName()));
            assertTrue(configuration.contains("additivity=\"false\""));
            assertFalse(configuration.contains("System.out"));
            assertFalse(configuration.contains("%ex"));
        }
    }
}

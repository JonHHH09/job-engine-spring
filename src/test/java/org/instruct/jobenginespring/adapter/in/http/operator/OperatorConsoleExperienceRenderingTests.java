package org.instruct.jobenginespring.adapter.in.http.operator;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorConsoleExperienceRenderingTests {

    @Test
    void rendersExperienceTitleAndCompanyFromTheServedConsoleScript() throws Exception {
        Path app = Files.createTempFile("operator-console-app-", ".js");
        try (InputStream source = getClass().getResourceAsStream("/operator-ui/app.js")) {
            assertTrue(source != null, "operator console script must be packaged for tests");
            Files.copy(source, app, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Path harness = Path.of(getClass().getResource("/operator-ui/experience-rendering-regression.js").toURI());
        Process process = new ProcessBuilder("node", harness.toString(), app.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "JavaScript regression harness timed out");
        String output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("PASS"), output);
    }
}

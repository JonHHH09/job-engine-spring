package org.instruct.jobenginespring.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpLocalOnlyStartupGuardTests {

    @Test
    void acceptsStdioServerWithNoWebApplication() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.server.stdio", "true")
                .withProperty("spring.main.web-application-type", "none");

        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(environment));
        assertDoesNotThrow(() -> new McpLocalOnlyStartupGuard(environment).run(null));
    }

    @Test
    void rejectsMissingAndNullConfigurationInputs() {
        MockEnvironment missingStdio = new MockEnvironment()
                .withProperty("spring.main.web-application-type", "none");

        IllegalStateException missingException = assertThrows(
                IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(missingStdio)
        );
        NullPointerException nullEnvironment = assertThrows(
                NullPointerException.class,
                () -> new McpLocalOnlyStartupGuard(null)
        );

        assertTrue(missingException.getMessage().contains("<unset>"));
        assertTrue(nullEnvironment.getMessage().contains("environment"));
    }

    @Test
    void rejectsNonStdioMcpConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.server.stdio", "false")
                .withProperty("spring.main.web-application-type", "none");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.ai.mcp.server.stdio"));
    }

    @Test
    void rejectsWebApplicationConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.server.stdio", "true")
                .withProperty("spring.main.web-application-type", "servlet");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.main.web-application-type"));
    }
}

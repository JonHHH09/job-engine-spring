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
                .withProperty("job-engine.mcp.transport", "stdio")
                .withProperty("spring.ai.mcp.server.stdio", "true")
                .withProperty("spring.main.web-application-type", "none");

        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(environment));
        assertDoesNotThrow(() -> new McpLocalOnlyStartupGuard(environment).run(null));
    }

    @Test
    void rejectsMissingAndNullConfigurationInputs() {
        MockEnvironment missingStdio = new MockEnvironment()
                .withProperty("job-engine.mcp.transport", "stdio")
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
    void rejectsInvalidStdioConfiguration() {
        MockEnvironment disabledStdio = new MockEnvironment()
                .withProperty("job-engine.mcp.transport", "stdio")
                .withProperty("spring.ai.mcp.server.stdio", "false")
                .withProperty("spring.main.web-application-type", "none");
        MockEnvironment webApplication = new MockEnvironment()
                .withProperty("job-engine.mcp.transport", "stdio")
                .withProperty("spring.ai.mcp.server.stdio", "true")
                .withProperty("spring.main.web-application-type", "servlet");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(disabledStdio)).getMessage().contains("stdio"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(webApplication)).getMessage().contains("web-application-type"));
    }

    @Test
    void acceptsLoopbackAndGuardedContainerStreamableHttp() {
        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(
                streamableHttpEnvironment("127.0.0.1", "false")
        ));
        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(
                streamableHttpEnvironment("localhost", "false")
        ));
        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(
                streamableHttpEnvironment("::1", "false")
        ));
        assertDoesNotThrow(() -> McpLocalOnlyStartupGuard.validate(
                streamableHttpEnvironment("0.0.0.0", "true")
        ));
    }

    @Test
    void rejectsUnsafeStreamableHttpConfigurations() {
        MockEnvironment publicHost = streamableHttpEnvironment("0.0.0.0", "false");
        MockEnvironment publicAddress = streamableHttpEnvironment("192.0.2.10", "true");
        MockEnvironment wrongProtocol = streamableHttpEnvironment("127.0.0.1", "false")
                .withProperty("spring.ai.mcp.server.protocol", "sse");
        MockEnvironment stdioEnabled = streamableHttpEnvironment("127.0.0.1", "false")
                .withProperty("spring.ai.mcp.server.stdio", "true");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(publicHost)).getMessage().contains("server.address"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(publicAddress)).getMessage().contains("server.address"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(wrongProtocol)).getMessage().contains("protocol"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(stdioEnabled)).getMessage().contains("stdio"));
    }

    @Test
    void rejectsUnknownTransport() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("job-engine.mcp.transport", "http");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> McpLocalOnlyStartupGuard.validate(environment)
        );

        assertTrue(exception.getMessage().contains("job-engine.mcp.transport"));
    }

    private static MockEnvironment streamableHttpEnvironment(String address, String containerized) {
        return new MockEnvironment()
                .withProperty("job-engine.mcp.transport", "streamable-http")
                .withProperty("job-engine.mcp.containerized", containerized)
                .withProperty("spring.ai.mcp.server.stdio", "false")
                .withProperty("spring.ai.mcp.server.protocol", "streamable")
                .withProperty("spring.main.web-application-type", "servlet")
                .withProperty("server.address", address);
    }
}

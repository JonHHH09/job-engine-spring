package org.instruct.jobenginespring.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Fails closed unless MCP uses the supported local STDIO or Streamable HTTP runtime.
 */
@Component
public class McpLocalOnlyStartupGuard implements ApplicationRunner {

    private final Environment environment;

    public McpLocalOnlyStartupGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        validate(environment);
    }

    static void validate(Environment environment) {
        String transport = normalize(environment.getProperty("job-engine.mcp.transport"));
        if ("stdio".equals(transport)) {
            requireEquals(environment, "spring.ai.mcp.server.stdio", "true");
            requireEquals(environment, "spring.main.web-application-type", "none");
            return;
        }
        if ("streamable-http".equals(transport)) {
            requireEquals(environment, "spring.ai.mcp.server.stdio", "false");
            requireEquals(environment, "spring.ai.mcp.server.protocol", "streamable");
            requireEquals(environment, "spring.main.web-application-type", "servlet");
            requireSafeHttpBind(environment);
            return;
        }
        throw localOnlyConfigurationException(
                "job-engine.mcp.transport", "stdio or streamable-http", transport
        );
    }

    private static void requireSafeHttpBind(Environment environment) {
        String address = normalize(environment.getProperty("server.address"));
        if ("127.0.0.1".equals(address) || "localhost".equals(address) || "::1".equals(address)) {
            return;
        }
        String containerized = normalize(environment.getProperty("job-engine.mcp.containerized"));
        if ("0.0.0.0".equals(address) && "true".equals(containerized)) {
            return;
        }
        throw localOnlyConfigurationException(
                "server.address", "a loopback address (or 0.0.0.0 only in the guarded container runtime)", address
        );
    }

    private static void requireEquals(Environment environment, String property, String expected) {
        String actual = normalize(environment.getProperty(property));
        if (!expected.equals(actual)) {
            throw localOnlyConfigurationException(property, expected, actual);
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException localOnlyConfigurationException(String property, String expected, String actual) {
        return new IllegalStateException("job-engine-spring MCP is local-only; property " + property
                + " must be " + expected + " but was " + (actual == null ? "<unset>" : actual));
    }
}

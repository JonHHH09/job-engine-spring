package org.instruct.jobenginespring.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Fails closed when the MCP server is configured as anything other than a local STDIO subprocess.
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
        requireTrue(environment, "spring.ai.mcp.server.stdio", "true");
        requireEquals(environment, "spring.main.web-application-type", "none");
    }

    private static void requireTrue(Environment environment, String property, String expected) {
        String actual = normalize(environment.getProperty(property));
        if (!expected.equals(actual)) {
            throw localOnlyConfigurationException(property, expected, actual);
        }
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

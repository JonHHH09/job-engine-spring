package org.instruct.jobenginespring.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * Fails closed unless MCP uses the supported local STDIO or Streamable HTTP runtime.
 */
public class McpLocalOnlyStartupGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
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
            requireExact(environment, "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
            requireValidPort(environment);
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

    private static void requireExact(Environment environment, String property, String expected) {
        String actual = environment.getProperty(property);
        if (!expected.equals(actual)) {
            throw localOnlyConfigurationException(property, expected, actual);
        }
    }

    private static void requireValidPort(Environment environment) {
        String actual = environment.getProperty("server.port");
        if (actual == null) {
            throw localOnlyConfigurationException("server.port", "an integer from 1 through 65535", null);
        }
        try {
            int port = Integer.parseInt(actual);
            if (port >= 1 && port <= 65_535) {
                return;
            }
        } catch (NumberFormatException ignored) {
            // Report the same fail-closed configuration error for absent and non-numeric values.
        }
        throw localOnlyConfigurationException("server.port", "an integer from 1 through 65535", actual);
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException localOnlyConfigurationException(String property, String expected, String actual) {
        return new IllegalStateException("job-engine-spring MCP is local-only; property " + property
                + " must be " + expected + " but was " + (actual == null ? "<unset>" : actual));
    }
}

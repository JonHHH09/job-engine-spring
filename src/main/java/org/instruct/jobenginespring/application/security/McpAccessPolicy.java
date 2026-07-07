package org.instruct.jobenginespring.application.security;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class McpAccessPolicy {

    private final String accessToken;
    private final boolean permitAll;

    @Autowired
    public McpAccessPolicy(@Value("${job-engine.mcp.access-token:}") String accessToken) {
        this.accessToken = normalize(accessToken);
        this.permitAll = false;
    }

    private McpAccessPolicy(String accessToken, boolean permitAll) {
        this.accessToken = normalize(accessToken);
        this.permitAll = permitAll;
    }

    public static McpAccessPolicy configured(String accessToken) {
        return new McpAccessPolicy(accessToken);
    }

    public static McpAccessPolicy permitAllForTests() {
        return new McpAccessPolicy(null, true);
    }

    public void authorize(String suppliedToken, String operation) {
        if (permitAll) {
            return;
        }
        if (accessToken == null) {
            throw denied(operation, "MCP access token is not configured");
        }
        String safeSuppliedToken = normalize(suppliedToken);
        if (safeSuppliedToken == null || !constantTimeEquals(accessToken, safeSuppliedToken)) {
            throw denied(operation, "MCP access token is invalid");
        }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApplicationException denied(String operation, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.AUTHORIZATION_ERROR,
                "MCP access denied",
                Map.of("operation", operation, "reason", reason),
                null
        );
    }
}

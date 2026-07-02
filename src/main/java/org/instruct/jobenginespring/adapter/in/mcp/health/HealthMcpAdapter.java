package org.instruct.jobenginespring.adapter.in.mcp.health;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.health.DatabaseHealthService;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthReport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HealthMcpAdapter {

    private final DatabaseHealthService databaseHealthService;

    @McpTool(
            name = "health",
            description = "Check application database readiness without returning connection details or secrets."
    )
    public DatabaseHealthReport health() {
        return databaseHealthService.checkHealth();
    }
}

package org.instruct.jobenginespring.adapter.in.mcp.health;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.health.ApplicationHealthService;
import org.instruct.jobenginespring.application.health.ApplicationHealthService.ApplicationHealthReport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HealthMcpAdapter {

    private final ApplicationHealthService applicationHealthService;

    @McpTool(
            name = "health",
            description = "Check database readiness and sanitized generated-resume cleanup backlog health."
    )
    public ApplicationHealthReport health() {
        return applicationHealthService.checkHealth();
    }
}

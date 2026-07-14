package org.instruct.jobenginespring.adapter.in.mcp.health;

import org.instruct.jobenginespring.application.health.ApplicationHealthService;
import org.instruct.jobenginespring.application.health.ApplicationHealthService.ApplicationHealthReport;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthMetadata;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthStatus;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthReport;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthMcpAdapterTests {

    private static final Instant CHECKED_AT = Instant.parse("2026-07-02T21:15:00Z");

    private final ApplicationHealthService applicationHealthService = mock(ApplicationHealthService.class);
    private final HealthMcpAdapter adapter = new HealthMcpAdapter(applicationHealthService);

    @Test
    void exposesStableHealthToolName() {
        Set<String> toolNames = Arrays.stream(HealthMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("health"), toolNames);
    }

    @Test
    void healthToolDelegatesToService() {
        ApplicationHealthReport report = new ApplicationHealthReport(
                DatabaseHealthStatus.UP,
                DatabaseHealthErrorCategory.NONE,
                new DatabaseHealthMetadata(CHECKED_AT, 1, 1, 0),
                new CleanupHealthReport(CleanupHealthStatus.HEALTHY, 0, 0, 0, false)
        );
        when(applicationHealthService.checkHealth()).thenReturn(report);

        assertSame(report, adapter.health());

        verify(applicationHealthService).checkHealth();
    }
}

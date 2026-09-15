package org.instruct.jobenginespring.adapter.in.http.operator;

import org.instruct.jobenginespring.application.health.ApplicationHealthService;
import org.instruct.jobenginespring.application.health.ApplicationHealthService.ApplicationHealthReport;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthMetadata;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthStatus;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthReport;
import org.instruct.jobenginespring.application.health.GeneratedResumeCleanupHealthService.CleanupHealthStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthOperatorControllerTests {

    @Test
    void returnsSanitizedHealthReport() throws Exception {
        ApplicationHealthService applicationHealthService = mock(ApplicationHealthService.class);
        ApplicationHealthReport report = new ApplicationHealthReport(
                DatabaseHealthStatus.UP,
                DatabaseHealthErrorCategory.NONE,
                new DatabaseHealthMetadata(Instant.parse("2026-08-26T00:00:00Z"), 3, 3, 0),
                new CleanupHealthReport(CleanupHealthStatus.HEALTHY, 0, 0, 0, 0, false)
        );
        when(applicationHealthService.checkHealth()).thenReturn(report);

        mvc(applicationHealthService).perform(get("/api/operator/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.errorCategory").value("NONE"))
                .andExpect(jsonPath("$.metadata.totalChecks").value(3))
                .andExpect(jsonPath("$.generatedResumeCleanup.status").value("HEALTHY"));
    }

    @Test
    void propagatesDatabaseDownStatus() throws Exception {
        ApplicationHealthService applicationHealthService = mock(ApplicationHealthService.class);
        ApplicationHealthReport report = new ApplicationHealthReport(
                DatabaseHealthStatus.DOWN,
                DatabaseHealthErrorCategory.CONNECTION_UNAVAILABLE,
                new DatabaseHealthMetadata(Instant.parse("2026-08-26T00:00:00Z"), 3, 0, 3),
                new CleanupHealthReport(CleanupHealthStatus.UNKNOWN, 0, 0, 0, 0, false)
        );
        when(applicationHealthService.checkHealth()).thenReturn(report);

        mvc(applicationHealthService).perform(get("/api/operator/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.errorCategory").value("CONNECTION_UNAVAILABLE"));
    }

    private static MockMvc mvc(ApplicationHealthService applicationHealthService) {
        return MockMvcBuilders.standaloneSetup(new HealthOperatorController(applicationHealthService))
                .setControllerAdvice(new OperatorProblemHandler())
                .build();
    }
}

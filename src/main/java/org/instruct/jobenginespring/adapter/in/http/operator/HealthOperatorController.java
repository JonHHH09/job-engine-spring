package org.instruct.jobenginespring.adapter.in.http.operator;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.health.ApplicationHealthService;
import org.instruct.jobenginespring.application.health.ApplicationHealthService.ApplicationHealthReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class HealthOperatorController {

    private final ApplicationHealthService applicationHealthService;

    @GetMapping("/api/operator/v1/health")
    ApplicationHealthReport health() {
        return applicationHealthService.checkHealth();
    }
}

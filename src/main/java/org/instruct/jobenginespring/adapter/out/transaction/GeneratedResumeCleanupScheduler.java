package org.instruct.jobenginespring.adapter.out.transaction;

import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class GeneratedResumeCleanupScheduler {

    private final GeneratedResumeCleanupService cleanupService;

    public GeneratedResumeCleanupScheduler(GeneratedResumeCleanupService cleanupService) {
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
    }

    @Scheduled(
            fixedDelayString = "${job-engine.pdf-generation.cleanup-retry-delay:60000}",
            initialDelayString = "${job-engine.pdf-generation.cleanup-retry-initial-delay:60000}"
    )
    public void retryDueTasks() {
        cleanupService.retryDueTasks();
    }

    @Scheduled(
            fixedDelayString = "${job-engine.pdf-generation.cleanup-retention-delay:PT24H}",
            initialDelayString = "${job-engine.pdf-generation.cleanup-retention-initial-delay:PT5M}"
    )
    public void purgeCompletedTasks() {
        cleanupService.purgeCompletedTasks();
    }
}

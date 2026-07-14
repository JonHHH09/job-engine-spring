package org.instruct.jobenginespring.adapter.out.transaction;

import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class GeneratedResumeCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedResumeCleanupScheduler.class);

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
        try {
            cleanupService.purgeCompletedTasks();
        } catch (RuntimeException exception) {
            LOGGER.error("event=generated_resume_cleanup_retention_purge_failure action=retry_next_schedule");
        }
    }
}

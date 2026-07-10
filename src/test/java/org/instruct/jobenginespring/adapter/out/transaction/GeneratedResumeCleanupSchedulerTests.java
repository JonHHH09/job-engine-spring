package org.instruct.jobenginespring.adapter.out.transaction;

import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GeneratedResumeCleanupSchedulerTests {

    @Test
    void delegatesScheduledRetryToApplicationService() {
        GeneratedResumeCleanupService service = mock(GeneratedResumeCleanupService.class);

        new GeneratedResumeCleanupScheduler(service).retryDueTasks();

        verify(service).retryDueTasks();
    }
}

package org.instruct.jobenginespring.adapter.out.transaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GeneratedResumeCleanupSchedulerTests {

    @Test
    void delegatesScheduledRetryToApplicationService() {
        GeneratedResumeCleanupService service = mock(GeneratedResumeCleanupService.class);

        new GeneratedResumeCleanupScheduler(service).retryDueTasks();

        verify(service).retryDueTasks();
    }

    @Test
    void retryScanFailureIsContainedAndLoggedWithoutSensitiveFailureDetails() {
        var service = mock(GeneratedResumeCleanupService.class);
        doThrow(new IllegalStateException("jdbc:postgresql://private password=secret"))
                .when(service).retryDueTasks();
        var appender = captureSchedulerLogs();

        assertDoesNotThrow(() -> new GeneratedResumeCleanupScheduler(service).retryDueTasks());

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals(
                "event=generated_resume_cleanup_retry_scan_failure action=retry_next_schedule",
                event.getFormattedMessage()
        );
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("jdbc:postgresql"));
        assertFalse(event.getFormattedMessage().contains("secret"));
    }

    @Test
    void delegatesScheduledRetentionToApplicationService() {
        GeneratedResumeCleanupService service = mock(GeneratedResumeCleanupService.class);

        new GeneratedResumeCleanupScheduler(service).purgeCompletedTasks();

        verify(service).purgeCompletedTasks();
    }

    @Test
    void retentionFailureIsContainedAndLoggedWithoutSensitiveFailureDetails() {
        var service = mock(GeneratedResumeCleanupService.class);
        doThrow(new IllegalStateException("private=/Users/jh/resume.pdf password=secret"))
                .when(service).purgeCompletedTasks();
        var appender = captureSchedulerLogs();

        assertDoesNotThrow(() -> new GeneratedResumeCleanupScheduler(service).purgeCompletedTasks());

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals(
                "event=generated_resume_cleanup_retention_purge_failure action=retry_next_schedule",
                event.getFormattedMessage()
        );
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("/Users/jh"));
        assertFalse(event.getFormattedMessage().contains("secret"));
    }

    private static ListAppender<ILoggingEvent> captureSchedulerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeneratedResumeCleanupScheduler.class);
        logger.setLevel(Level.ERROR);
        logger.detachAndStopAllAppenders();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}

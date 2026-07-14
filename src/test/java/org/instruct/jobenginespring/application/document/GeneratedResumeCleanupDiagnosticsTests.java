package org.instruct.jobenginespring.application.document;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedResumeCleanupDiagnosticsTests {

    private static final Instant NOW = Instant.parse("2026-07-14T16:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void failedDeletionLogsSanitizedWarningWithoutThrowableOrPrivatePath() {
        var preparation = mock(GeneratedResumeCleanupPreparation.class);
        var deletion = mock(GeneratedResumeCleanupFileDeletion.class);
        var finalizer = mock(GeneratedResumeCleanupFinalizer.class);
        var prepared = new GeneratedResumeCleanupPreparation.PreparedCleanup(
                "/Users/jh/private/resume.pdf",
                true
        );
        when(preparation.prepare(TASK_ID)).thenReturn(Optional.of(prepared));
        doThrow(new IllegalStateException("cannot delete /Users/jh/private/resume.pdf secret-token"))
                .when(deletion).deleteIfRequired(prepared);
        var appender = captureCleanupLogs();

        new GeneratedResumeCleanupExecutor(
                preparation,
                deletion,
                finalizer,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).attemptSafely(TASK_ID);

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("/Users/jh"));
        assertFalse(event.getFormattedMessage().contains("secret-token"));
    }

    @Test
    void durableStateFailureLogsSanitizedErrorWithoutStackTrace() {
        var preparation = mock(GeneratedResumeCleanupPreparation.class);
        when(preparation.prepare(TASK_ID))
                .thenThrow(new IllegalStateException("jdbc:postgresql://private password=secret"));
        var appender = captureCleanupLogs();

        new GeneratedResumeCleanupExecutor(
                preparation,
                mock(GeneratedResumeCleanupFileDeletion.class),
                mock(GeneratedResumeCleanupFinalizer.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).attemptSafely(TASK_ID);

        ILoggingEvent event = appender.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("jdbc:postgresql"));
        assertFalse(event.getFormattedMessage().contains("secret"));
    }

    private static ListAppender<ILoggingEvent> captureCleanupLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeneratedResumeCleanupExecutor.class);
        logger.setLevel(Level.WARN);
        logger.detachAndStopAllAppenders();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}

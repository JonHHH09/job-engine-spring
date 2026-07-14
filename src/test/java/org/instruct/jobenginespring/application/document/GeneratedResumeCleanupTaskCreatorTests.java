package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeCleanupRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedResumeCleanupTaskCreatorTests {

    @Test
    void createsTaskAndValidatesInputs() {
        var repository = mock(GeneratedResumeCleanupRepository.class);
        var creator = new GeneratedResumeCleanupTaskCreator(repository);
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(repository.enqueue("resume.pdf", now)).thenReturn(taskId);
        when(repository.enqueue(documentId, "resume.pdf", now)).thenReturn(taskId);

        assertEquals(taskId, creator.enqueue("resume.pdf", now));
        verify(repository).enqueue("resume.pdf", now);
        assertEquals(taskId, creator.enqueue(documentId, "resume.pdf", now));
        verify(repository).enqueue(documentId, "resume.pdf", now);
        assertThrows(NullPointerException.class, () -> creator.enqueue(null, now));
        assertThrows(NullPointerException.class, () -> creator.enqueue("resume.pdf", null));
        assertThrows(NullPointerException.class, () -> new GeneratedResumeCleanupTaskCreator(null));
    }

    @Test
    void repositoryDefaultsRemainCompatibleWithFileOnlyImplementations() {
        var repository = mock(GeneratedResumeCleanupRepository.class, CALLS_REAL_METHODS);
        Instant now = Instant.parse("2026-07-14T12:00:00Z");
        UUID taskId = UUID.randomUUID();
        when(repository.enqueue("resume.pdf", now)).thenReturn(taskId);

        assertEquals(taskId, repository.enqueue(UUID.randomUUID(), "resume.pdf", now));
        assertTrue(repository.findDocumentId(taskId).isEmpty());
    }
}

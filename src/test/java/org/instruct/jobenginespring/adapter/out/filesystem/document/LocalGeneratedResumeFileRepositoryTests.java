package org.instruct.jobenginespring.adapter.out.filesystem.document;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalGeneratedResumeFileRepositoryTests {

    @TempDir
    Path tempDir;

    @Test
    void deletesExistingFileAndAcceptsMissingFile() throws Exception {
        LocalGeneratedResumeFileRepository repository = repository();
        Path generated = Files.writeString(tempDir.resolve("resume.pdf"), "private");

        repository.deleteIfExists(generated.toString());

        assertFalse(Files.exists(generated));
        assertDoesNotThrow(() -> repository.deleteIfExists(generated.toString()));
    }

    @Test
    void mapsInvalidPathsAndIoFailuresToSafeApplicationError() throws Exception {
        LocalGeneratedResumeFileRepository repository = repository();
        ApplicationException invalid = assertThrows(
                ApplicationException.class,
                () -> repository.deleteIfExists("bad\0path")
        );
        assertSafeCleanupError(invalid);

        Path nonEmptyDirectory = Files.createDirectory(tempDir.resolve("not-a-file"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "content");
        ApplicationException ioFailure = assertThrows(
                ApplicationException.class,
                () -> repository.deleteIfExists(nonEmptyDirectory.toString())
        );
        assertSafeCleanupError(ioFailure);
    }

    @Test
    void refusesToDeletePathsOutsideConfiguredGeneratedResumeRoots() throws Exception {
        LocalGeneratedResumeFileRepository repository = repository();
        Path outside = Files.createTempFile(tempDir.getParent(), "outside-resume-", ".pdf");
        try {
            ApplicationException failure = assertThrows(
                    ApplicationException.class,
                    () -> repository.deleteIfExists(outside.toString())
            );

            assertSafeCleanupError(failure);
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void requiresAtLeastOneGeneratedResumeRoot() {
        assertThrows(IllegalArgumentException.class, () -> new LocalGeneratedResumeFileRepository());
    }

    private LocalGeneratedResumeFileRepository repository() {
        return new LocalGeneratedResumeFileRepository(tempDir);
    }

    private static void assertSafeCleanupError(ApplicationException exception) {
        org.junit.jupiter.api.Assertions.assertEquals("internal_error", exception.errorCode().code());
        org.junit.jupiter.api.Assertions.assertEquals("Generated resume file could not be removed", exception.safeMessage());
        org.junit.jupiter.api.Assertions.assertTrue(exception.details().isEmpty());
    }
}

package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileImportPolicyTests {

    @Test
    void blankImportRootUsesDefaultImportRoot() throws IOException {
        Path defaultRoot = Path.of(LocalFileImportPolicy.DEFAULT_IMPORT_ROOT).toAbsolutePath().normalize();
        Path testDirectory = Files.createTempDirectory(Files.createDirectories(defaultRoot), "policy-");
        Path allowedFile = Files.writeString(testDirectory.resolve("resume.pdf"), "%PDF-1.7\nfixture");

        try {
            Path allowedPath = LocalFileImportPolicy.rootedAt(" ").requireAllowed(allowedFile);

            assertEquals(allowedFile.toRealPath(), allowedPath);
        } finally {
            Files.deleteIfExists(allowedFile);
            Files.deleteIfExists(testDirectory);
        }
    }

    @Test
    void nullImportRootUsesDefaultImportRoot() throws IOException {
        Path defaultRoot = Path.of(LocalFileImportPolicy.DEFAULT_IMPORT_ROOT).toAbsolutePath().normalize();
        Path testDirectory = Files.createTempDirectory(Files.createDirectories(defaultRoot), "policy-");
        Path allowedFile = Files.writeString(testDirectory.resolve("resume.pdf"), "%PDF-1.7\nfixture");

        try {
            Path allowedPath = LocalFileImportPolicy.rootedAt(null).requireAllowed(allowedFile);

            assertEquals(allowedFile.toRealPath(), allowedPath);
        } finally {
            Files.deleteIfExists(allowedFile);
            Files.deleteIfExists(testDirectory);
        }
    }

    @Test
    void invalidImportRootFailsWithConfigurationValidationError() {
        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> LocalFileImportPolicy.rootedAt("bad\0root")
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("importRoot", exception.details().get("field"));
        assertEquals("must be a valid file path", exception.details().get("reason"));
    }

    @Test
    void nonblankExistingImportRootAllowsFilesUnderRoot() throws IOException {
        Path importRoot = Files.createTempDirectory("policy-root-");
        Path allowedFile = Files.writeString(importRoot.resolve("resume.pdf"), "%PDF-1.7\nfixture");

        try {
            Path allowedPath = LocalFileImportPolicy.rootedAt(importRoot.toString()).requireAllowed(allowedFile);

            assertEquals(allowedFile.toRealPath(), allowedPath);
        } finally {
            Files.deleteIfExists(allowedFile);
            Files.deleteIfExists(importRoot);
        }
    }

    @Test
    void missingFileFailsWithReadableValidationError() throws IOException {
        Path importRoot = Files.createTempDirectory("policy-root-");

        try {
            ApplicationException exception = assertThrows(
                    ApplicationException.class,
                    () -> LocalFileImportPolicy.rootedAt(importRoot.toString()).requireAllowed(importRoot.resolve("missing.pdf"))
            );

            assertEquals("validation_error", exception.errorCode().code());
            assertEquals("path", exception.details().get("field"));
            assertEquals("file is not readable", exception.details().get("reason"));
            assertTrue(exception.getMessage().contains("Invalid document storage request"));
        } finally {
            Files.deleteIfExists(importRoot);
        }
    }

    @Test
    void missingImportRootRejectsReadableFileOutsideRoot() throws IOException {
        Path readableFile = Files.writeString(Files.createTempFile("policy-readable-", ".pdf"), "%PDF-1.7\nfixture");
        Path missingRoot = readableFile.getParent().resolve("missing-root");

        try {
            ApplicationException exception = assertThrows(
                    ApplicationException.class,
                    () -> LocalFileImportPolicy.rootedAt(missingRoot.toString()).requireAllowed(readableFile)
            );

            assertEquals("validation_error", exception.errorCode().code());
            assertEquals("path", exception.details().get("field"));
            assertEquals("file must be under configured import root", exception.details().get("reason"));
        } finally {
            Files.deleteIfExists(readableFile);
        }
    }
}

package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

final class LocalFileImportPolicy {

    static final String DEFAULT_IMPORT_ROOT = "tmp/imports";

    private final Path importRoot;

    private LocalFileImportPolicy(Path importRoot) {
        this.importRoot = importRoot;
    }

    static LocalFileImportPolicy unrestrictedForTests() {
        return new LocalFileImportPolicy(null);
    }

    static LocalFileImportPolicy rootedAt(String rawImportRoot) {
        return new LocalFileImportPolicy(toRootPath(rawImportRoot));
    }

    Path requireAllowed(Path path) {
        if (importRoot == null) {
            return path;
        }
        try {
            Path realPath = path.toRealPath();
            Path realRoot = importRoot.toFile().exists() ? importRoot.toRealPath() : importRoot;
            if (!realPath.startsWith(realRoot)) {
                throw validation("path", "file must be under configured import root");
            }
            return realPath;
        } catch (IOException exception) {
            throw validation("path", "file is not readable");
        }
    }

    private static Path toRootPath(String rawImportRoot) {
        try {
            String root = rawImportRoot == null || rawImportRoot.isBlank() ? DEFAULT_IMPORT_ROOT : rawImportRoot;
            return Path.of(root).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Invalid document import configuration",
                    Map.of("field", "importRoot", "reason", "must be a valid file path"),
                    exception
            );
        }
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid document storage request",
                Map.of("field", field, "reason", reason),
                null
        );
    }
}

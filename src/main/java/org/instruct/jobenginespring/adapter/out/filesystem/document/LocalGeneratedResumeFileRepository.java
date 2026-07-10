package org.instruct.jobenginespring.adapter.out.filesystem.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class LocalGeneratedResumeFileRepository implements GeneratedResumeFileRepository {

    private final List<Path> allowedRoots;

    @Autowired
    public LocalGeneratedResumeFileRepository(
            @Value("${job-engine.pdf-generation.output-dir:tmp/generated-pdfs}") String generalOutputDirectory,
            @Value("${job-engine.pdf-generation.master-resume-output-dir:tmp/generated-pdfs/master-resume}") String masterResumeOutputDirectory,
            @Value("${job-engine.pdf-generation.canadian-resume-output-dir:tmp/generated-pdfs/canadian-resume}") String canadianResumeOutputDirectory
    ) {
        this(
                Path.of(generalOutputDirectory),
                Path.of(masterResumeOutputDirectory),
                Path.of(canadianResumeOutputDirectory)
        );
    }

    public LocalGeneratedResumeFileRepository(Path... allowedRoots) {
        Objects.requireNonNull(allowedRoots, "allowedRoots must not be null");
        this.allowedRoots = Arrays.stream(allowedRoots)
                .map(root -> Objects.requireNonNull(root, "allowed root must not be null").toAbsolutePath().normalize())
                .distinct()
                .toList();
        if (this.allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("at least one generated resume root is required");
        }
    }

    @Override
    public void deleteIfExists(String filePath) {
        try {
            Path candidate = Path.of(filePath).toAbsolutePath().normalize();
            if (allowedRoots.stream().noneMatch(candidate::startsWith)) {
                throw cleanupFailure(null);
            }
            Files.deleteIfExists(candidate);
        } catch (InvalidPathException | IOException exception) {
            throw cleanupFailure(exception);
        }
    }

    private static ApplicationException cleanupFailure(Exception cause) {
        return new ApplicationException(
                ApplicationErrorCode.INTERNAL_ERROR,
                "Generated resume file could not be removed",
                Map.of(),
                cause
        );
    }
}

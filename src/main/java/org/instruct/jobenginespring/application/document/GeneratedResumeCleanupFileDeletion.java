package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Performs generated-resume filesystem cleanup with any caller transaction suspended. */
@Service
public class GeneratedResumeCleanupFileDeletion {

    private final GeneratedResumeFileRepository fileRepository;

    public GeneratedResumeCleanupFileDeletion(GeneratedResumeFileRepository fileRepository) {
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteIfRequired(GeneratedResumeCleanupPreparation.PreparedCleanup prepared) {
        if (prepared.deleteFile()) {
            fileRepository.deleteIfExists(prepared.filePath());
        }
    }
}

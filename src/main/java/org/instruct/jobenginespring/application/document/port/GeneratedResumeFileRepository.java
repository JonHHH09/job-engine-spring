package org.instruct.jobenginespring.application.document.port;

/** Removes runtime files that were created for private generated resumes. */
public interface GeneratedResumeFileRepository {

    void deleteIfExists(String filePath);
}

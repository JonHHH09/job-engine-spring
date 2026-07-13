package org.instruct.jobenginespring.application.resume.port;

import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository {

    Optional<Resume> findByProfileJobFormat(UUID profileId, UUID jobId, String format);

    List<ResumeVariant> findVariants(UUID resumeId);

    ReplaceResult replaceGermanyResume(ResumeAggregateWrite write);

    record ResumeAggregateWrite(
            Resume resume,
            List<VariantWrite> variants
    ) {
    }

    record VariantWrite(
            ResumeVariant variant,
            List<SectionWrite> sections
    ) {
    }

    record SectionWrite(
            ResumeSection section,
            List<EntryWrite> entries
    ) {
    }

    record EntryWrite(
            ResumeEntry entry,
            List<ResumeEntryBullet> bullets
    ) {
    }

    record ReplaceResult(
            Resume resume,
            List<ResumeVariant> variants,
            List<ResumeVariant> previousVariants
    ) {
    }
}

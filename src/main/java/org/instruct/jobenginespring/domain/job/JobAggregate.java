package org.instruct.jobenginespring.domain.job;

import java.util.List;
import java.util.Objects;

public record JobAggregate(
        JobPosting job,
        List<JobSkill> skills,
        JobLinkIngestion linkIngestion,
        JobTextIngestion textIngestion
) {
    public JobAggregate {
        Objects.requireNonNull(job, "job must not be null");
        skills = skills == null ? List.of() : List.copyOf(skills);
        boolean hasLink = linkIngestion != null;
        boolean hasText = textIngestion != null;
        if (hasLink == hasText) {
            throw new IllegalArgumentException("job aggregate must contain exactly one provenance source");
        }
        if (hasLink) {
            requireMatchingJobId(job.id(), linkIngestion.jobId(), "linkIngestion");
            requireSourceMethod(job.sourceMethod(), "link");
        }
        if (hasText) {
            requireMatchingJobId(job.id(), textIngestion.jobId(), "textIngestion");
            requireSourceMethod(job.sourceMethod(), "text");
        }
    }

    private static void requireMatchingJobId(java.util.UUID expected, java.util.UUID actual, String fieldName) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(fieldName + " jobId must match job id");
        }
    }

    private static void requireSourceMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("job sourceMethod must match provenance source");
        }
    }
}

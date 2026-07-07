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
    }
}

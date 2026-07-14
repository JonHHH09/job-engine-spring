package org.instruct.jobenginespring.application.job.port;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {

    List<JobPosting> listJobs();

    List<JobAggregate> listJobAggregates();

    Optional<JobAggregate> findJobAggregate(UUID jobId);

    Optional<JobAggregate> findByCanonicalFingerprint(String canonicalFingerprint);

    Optional<JobAggregate> findByNormalizedSourceUrl(String normalizedUrl);

    Optional<JobAggregate> findByInputTextHash(String inputTextHash);

    JobAggregate saveJobAggregate(JobAggregate aggregate);

    Optional<JobAggregate> updateJobAggregate(JobAggregate aggregate, long expectedRevision);

    boolean deleteJob(UUID jobId);
}

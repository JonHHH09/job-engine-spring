package org.instruct.jobenginespring.application.job.port;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.application.pagination.SearchCandidates;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {

    List<JobPosting> listJobs();

    default Page<JobPosting> listJobs(PageRequest request) {
        var jobs = listJobs().stream().limit(request.limit()).toList();
        return new Page<>(jobs, null);
    }

    Page<JobAggregate> listJobAggregates(PageRequest request);

    @Deprecated(forRemoval = false)
    default List<JobAggregate> listJobAggregates() {
        return listJobAggregates(PageRequest.of(null, null, "job-aggregates", "all")).items();
    }

    SearchCandidates<JobAggregate> searchJobCandidates(List<String> queryTokens, int limit);

    Optional<JobAggregate> findJobAggregate(UUID jobId);

    Optional<JobAggregate> findByCanonicalFingerprint(String canonicalFingerprint);

    Optional<JobAggregate> findByNormalizedSourceUrl(String normalizedUrl);

    Optional<JobAggregate> findByInputTextHash(String inputTextHash);

    JobAggregate saveJobAggregate(JobAggregate aggregate);

    JobAggregate updateJobAggregate(JobAggregate aggregate);

    boolean deleteJob(UUID jobId);
}

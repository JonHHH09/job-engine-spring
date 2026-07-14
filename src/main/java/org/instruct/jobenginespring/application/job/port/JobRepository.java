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

    List<JobAggregate> listJobAggregates();

    default SearchCandidates<JobAggregate> searchJobCandidates(List<String> queryTokens, int limit) {
        var aggregates = listJobAggregates();
        return new SearchCandidates<>(-1, aggregates);
    }

    Optional<JobAggregate> findJobAggregate(UUID jobId);

    Optional<JobAggregate> findByCanonicalFingerprint(String canonicalFingerprint);

    Optional<JobAggregate> findByNormalizedSourceUrl(String normalizedUrl);

    Optional<JobAggregate> findByInputTextHash(String inputTextHash);

    JobAggregate saveJobAggregate(JobAggregate aggregate);

    JobAggregate updateJobAggregate(JobAggregate aggregate);

    boolean deleteJob(UUID jobId);
}

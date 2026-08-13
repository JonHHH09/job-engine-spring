package org.instruct.jobenginespring.application.jobscan;

import org.instruct.jobenginespring.application.job.JobService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Verifies a scan-issued candidate before invoking the existing stored-job aggregate path. */
@Service
public class ArbeitnowJobImportService {
    private final ArbeitnowCandidateTokenCodec candidateTokens;
    private final JobService jobService;

    public ArbeitnowJobImportService(ArbeitnowCandidateTokenCodec candidateTokens, JobService jobService) {
        this.candidateTokens = Objects.requireNonNull(candidateTokens);
        this.jobService = Objects.requireNonNull(jobService);
    }

    public JobService.AddJobResult importCandidate(String candidateToken) {
        ArbeitnowCandidateTokenCodec.Candidate candidate = candidateTokens.verify(candidateToken);
        String employmentType = candidate.jobTypes().isEmpty() ? null : candidate.jobTypes().getFirst();
        return jobService.importTrustedArbeitnowJob(new JobService.TrustedArbeitnowImportRequest(
                candidate.canonicalUrl(), candidate.company(), candidate.title(), candidate.location(),
                candidate.descriptionExcerpt(), candidate.tags(), employmentType, candidate.remote(), candidate.postedAt()));
    }
}

package org.instruct.jobenginespring.adapter.out.hermes.job;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobPostingAnalysisPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "job-engine.job.analysis", name = "provider", havingValue = "unavailable", matchIfMissing = true)
public class UnavailableJobPostingAnalysisAdapter implements JobPostingAnalysisPort {

    @Override
    public JobPostingAnalysisResponse analyze(JobPostingAnalysisRequest request) {
        throw new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Job posting analysis provider is not configured",
                Map.of("provider", "hermes-cli"),
                null
        );
    }
}

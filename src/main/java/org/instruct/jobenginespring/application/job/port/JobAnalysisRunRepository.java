package org.instruct.jobenginespring.application.job.port;

import org.instruct.jobenginespring.domain.job.JobAnalysisRun;

import java.util.Optional;
import java.util.UUID;

public interface JobAnalysisRunRepository {

    JobAnalysisRun save(JobAnalysisRun analysisRun);

    Optional<JobAnalysisRun> findById(UUID analysisRunId);

    JobAnalysisRun update(JobAnalysisRun analysisRun);
}

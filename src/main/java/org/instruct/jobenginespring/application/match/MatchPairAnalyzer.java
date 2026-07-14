package org.instruct.jobenginespring.application.match;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;

import java.util.UUID;

public interface MatchPairAnalyzer {
    MatchReport analyze(UUID profileId, UUID jobId);

    MatchReport analyze(ProfileAggregate profile, JobAggregate job);
}

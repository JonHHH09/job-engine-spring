package org.instruct.jobenginespring.application.match;

import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.match.DeterministicMatchScorer;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "job-engine.job.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionalMatchPairAnalyzer implements MatchPairAnalyzer {
    private final ProfileRepository profiles;
    private final JobRepository jobs;
    private final MatchAnalysisRepository matches;
    private final DeterministicMatchScorer scorer;
    private final Clock clock;

    @Autowired
    public TransactionalMatchPairAnalyzer(ProfileRepository profiles, JobRepository jobs,
                                          MatchAnalysisRepository matches) {
        this(profiles, jobs, matches, new DeterministicMatchScorer(), Clock.systemUTC());
    }

    TransactionalMatchPairAnalyzer(ProfileRepository profiles, JobRepository jobs, MatchAnalysisRepository matches,
                                   DeterministicMatchScorer scorer, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.scorer = Objects.requireNonNull(scorer, "scorer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatchReport analyze(UUID profileId, UUID jobId) {
        var requiredProfileId = Objects.requireNonNull(profileId, "profileId must not be null");
        var requiredJobId = Objects.requireNonNull(jobId, "jobId must not be null");
        var profile = profiles.findProfileAggregate(requiredProfileId)
                .orElseThrow(() -> new IllegalArgumentException("profile not found: " + profileId));
        var job = jobs.findJobAggregate(requiredJobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId));
        return scoreAndSave(profile, job);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MatchReport analyze(ProfileAggregate profile, JobAggregate job) {
        return scoreAndSave(
                Objects.requireNonNull(profile, "profile must not be null"),
                Objects.requireNonNull(job, "job must not be null")
        );
    }

    private MatchReport scoreAndSave(ProfileAggregate profile, JobAggregate job) {
        return matches.saveReport(scorer.score(
                profile,
                job,
                clock.instant()
        ));
    }
}

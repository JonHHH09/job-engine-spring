package org.instruct.jobenginespring.application.match;

import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository.ReportWithRevisions;
import org.instruct.jobenginespring.domain.match.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "job-engine.job.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MatchAnalysisService {
    private final ProfileRepository profiles;
    private final JobRepository jobs;
    private final MatchAnalysisRepository matches;
    private final MatchPairAnalyzer pairAnalyzer;
    private final Clock clock;

    @Autowired
    public MatchAnalysisService(ProfileRepository profiles, JobRepository jobs, MatchAnalysisRepository matches,
                                MatchPairAnalyzer pairAnalyzer) {
        this(profiles, jobs, matches, pairAnalyzer, Clock.systemUTC());
    }

    public MatchAnalysisService(ProfileRepository profiles, JobRepository jobs, MatchAnalysisRepository matches) {
        this(profiles, jobs, matches, new TransactionalMatchPairAnalyzer(profiles, jobs, matches), Clock.systemUTC());
    }

    MatchAnalysisService(ProfileRepository profiles, JobRepository jobs, MatchAnalysisRepository matches,
                         DeterministicMatchScorer scorer, Clock clock) {
        this(profiles, jobs, matches, new TransactionalMatchPairAnalyzer(profiles, jobs, matches, scorer, clock), clock);
    }

    MatchAnalysisService(ProfileRepository profiles, JobRepository jobs, MatchAnalysisRepository matches,
                         MatchPairAnalyzer pairAnalyzer, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles); this.jobs = Objects.requireNonNull(jobs);
        this.matches = Objects.requireNonNull(matches); this.pairAnalyzer = Objects.requireNonNull(pairAnalyzer);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReportView analyze(UUID profileId, UUID jobId) {
        return new ReportView(pairAnalyzer.analyze(
                requireId(profileId, "profileId"), requireId(jobId, "jobId")), false);
    }

    public BatchResult analyzeAll(UUID profileId, Integer limit, String cursor) {
        var requiredProfileId = requireId(profileId, "profileId");
        var profile = profiles.findProfileAggregate(requiredProfileId)
                .orElseThrow(() -> new IllegalArgumentException("profile not found: " + profileId));
        var jobsPage = jobs.listJobAggregates(PageRequest.of(limit, cursor, "analyze-all-job-matches",
                "profile=" + requiredProfileId));
        var succeeded = new ArrayList<ReportView>();
        var failed = new ArrayList<PairFailure>();
        for (var job : jobsPage.items()) {
            try { succeeded.add(new ReportView(pairAnalyzer.analyze(profile, job), false)); }
            catch (RuntimeException exception) { failed.add(new PairFailure(profileId, job.job().id(), "analysis failed")); }
        }
        return new BatchResult(succeeded, failed, jobsPage.nextCursor());
    }

    @Deprecated(forRemoval = false)
    public BatchResult analyzeAll(UUID profileId) { return analyzeAll(profileId, null, null); }

    public ReportView getReport(UUID reportId) {
        var report = matches.findReportWithRevisions(requireId(reportId, "reportId"))
                .orElseThrow(() -> new IllegalArgumentException("match report not found: " + reportId));
        return view(report);
    }

    public List<ReportView> listReports(UUID profileId, UUID jobId) {
        return listReports(profileId, jobId, null, null).items();
    }

    public Page<ReportView> listReports(UUID profileId, UUID jobId, Integer limit, String cursor) {
        var filterIdentity = "profile=" + profileId + ";job=" + jobId;
        var page = matches.listReports(profileId, jobId,
                PageRequest.of(limit, cursor, "match-reports", filterIdentity));
        return new Page<>(page.items().stream().map(MatchAnalysisService::view).toList(), page.nextCursor());
    }

    @Transactional
    public ReviewResult submitReview(ReviewDraft draft) {
        Objects.requireNonNull(draft, "review must not be null");
        var now = clock.instant();
        var report = matches.findReport(requireId(draft.reportId(), "reportId"))
                .orElseThrow(() -> new IllegalArgumentException("match report not found: " + draft.reportId()));
        validateAdvisoryEvidence(report, draft.evidence());
        var provisional = new MatchReview(UUID.randomUUID(), draft.reportId(), draft.reviewer(), draft.model(),
                draft.reviewVersion(), draft.overallScore(), draft.outcome(), draft.blockerMismatch(), draft.components(),
                draft.evidence(), draft.summary(), now);
        var review = new MatchReview(provisional.fingerprint(), draft.reportId(), draft.reviewer(), draft.model(),
                draft.reviewVersion(), draft.overallScore(), draft.outcome(), draft.blockerMismatch(), draft.components(),
                draft.evidence(), draft.summary(), now);
        var saved = matches.saveReview(review);
        var policy = MatchDivergencePolicy.V1;
        var reasons = policy.reasons(report, saved);
        MatchDisagreement disagreement = null;
        if (!reasons.isEmpty()) {
            var defectCodes = saved.evidence().stream().filter(MatchEvidence::outcomeChangingDefect)
                    .map(MatchEvidence::fact).filter(MatchAdvisoryCodes::isEvidenceDefectCode).sorted().distinct().toList();
            var candidateId = MatchDisagreement.fingerprint(report.id(), policy.version(), reasons, defectCodes);
            disagreement = matches.saveDisagreement(new MatchDisagreement(candidateId, report.id(), saved.id(),
                    policy.version(), reasons, defectCodes, DisagreementStatus.PENDING_ESCALATION, null, now, now));
        }
        return new ReviewResult(saved, disagreement);
    }

    public MatchReview getReview(UUID reviewId) { return matches.findReview(requireId(reviewId, "reviewId"))
            .orElseThrow(() -> new IllegalArgumentException("match review not found: " + reviewId)); }
    public Page<MatchReview> listReviews(UUID reportId, Integer limit, String cursor) {
        return matches.listReviews(reportId, PageRequest.of(limit, cursor, "match-reviews", "report=" + reportId));
    }
    @Deprecated(forRemoval = false)
    public List<MatchReview> listReviews(UUID reportId) { return listReviews(reportId, null, null).items(); }
    public Page<MatchDisagreement> listDisagreements(UUID reportId, Integer limit, String cursor) {
        return matches.listDisagreements(reportId,
                PageRequest.of(limit, cursor, "match-disagreements", "report=" + reportId));
    }
    @Deprecated(forRemoval = false)
    public List<MatchDisagreement> listDisagreements(UUID reportId) {
        return listDisagreements(reportId, null, null).items();
    }

    @Transactional
    public MatchDisagreement acknowledgeDisagreement(UUID id, String linearIssueId) {
        if (linearIssueId != null && linearIssueId.isBlank()) throw new IllegalArgumentException("linearIssueId must not be blank");
        return changeDisagreement(id, DisagreementStatus.ACKNOWLEDGED,
                linearIssueId == null ? null : linearIssueId.trim());
    }
    @Transactional
    public MatchDisagreement linkDisagreement(UUID id, String linearIssueId) {
        if (linearIssueId == null || linearIssueId.isBlank()) throw new IllegalArgumentException("linearIssueId must not be blank");
        return changeDisagreement(id, DisagreementStatus.LINKED, linearIssueId.trim());
    }

    private MatchDisagreement changeDisagreement(UUID id, DisagreementStatus status, String issue) {
        var disagreement = matches.findDisagreement(requireId(id, "disagreementId"))
                .orElseThrow(() -> new IllegalArgumentException("match disagreement not found: " + id));
        return matches.updateDisagreement(new MatchDisagreement(disagreement.id(), disagreement.reportId(), disagreement.reviewId(),
                disagreement.policyVersion(), disagreement.reasons(), disagreement.evidenceDefectCodes(), status,
                issue == null ? disagreement.linearIssueId() : issue,
                disagreement.createdAt(), clock.instant()));
    }

    private static void validateAdvisoryEvidence(MatchReport report, List<MatchEvidence> evidence) {
        var baselineFacts = report.evidence().stream().map(MatchEvidence::fact).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var advisoryEvidence = evidence == null ? List.<MatchEvidence>of() : evidence;
        if (advisoryEvidence.stream().map(MatchEvidence::fact)
                .anyMatch(fact -> !baselineFacts.contains(fact) && !MatchAdvisoryCodes.isEvidenceDefectCode(fact))) {
            throw new IllegalArgumentException("advisory evidence must reuse a deterministic fact or an allowed defect code");
        }
    }

    private static ReportView view(ReportWithRevisions report) {
        return new ReportView(report.report(), report.stale());
    }
    private static UUID requireId(UUID id, String field) { return Objects.requireNonNull(id, field + " must not be null"); }
    public record ReportView(MatchReport report, boolean stale) { public ReportView { Objects.requireNonNull(report); } }
    public record PairFailure(UUID profileId, UUID jobId, String error) {}
    public record BatchResult(List<ReportView> succeeded, List<PairFailure> failed, String nextCursor) {
        public BatchResult { succeeded = List.copyOf(succeeded); failed = List.copyOf(failed); }
        public BatchResult(List<ReportView> succeeded, List<PairFailure> failed) { this(succeeded, failed, null); }
    }
    public record ReviewResult(MatchReview review, MatchDisagreement disagreement) {}
    public record ReviewDraft(UUID reportId, String reviewer, String model, String reviewVersion, int overallScore,
                              MatchOutcome outcome, boolean blockerMismatch, List<ComponentScore> components,
                              List<MatchEvidence> evidence, String summary) {}
}

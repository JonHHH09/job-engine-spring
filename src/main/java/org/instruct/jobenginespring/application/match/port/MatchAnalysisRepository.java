package org.instruct.jobenginespring.application.match.port;

import org.instruct.jobenginespring.domain.match.MatchDisagreement;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.match.MatchReview;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchAnalysisRepository {
    MatchReport saveReport(MatchReport report);
    Optional<MatchReport> findReport(UUID reportId);
    @Deprecated(forRemoval = false)
    default List<MatchReport> listReports(UUID profileId, UUID jobId) {
        return listReports(profileId, jobId, PageRequest.of(null, null, "match-reports",
                "profile=" + profileId + ";job=" + jobId)).items().stream().map(ReportWithRevisions::report).toList();
    }
    Optional<ReportWithRevisions> findReportWithRevisions(UUID reportId);
    Page<ReportWithRevisions> listReports(UUID profileId, UUID jobId, PageRequest request);
    MatchReview saveReview(MatchReview review);
    Optional<MatchReview> findReview(UUID reviewId);
    Page<MatchReview> listReviews(UUID reportId, PageRequest request);
    @Deprecated(forRemoval = false)
    default List<MatchReview> listReviews(UUID reportId) {
        return listReviews(reportId, PageRequest.of(null, null, "match-reviews", "report=" + reportId)).items();
    }
    MatchDisagreement saveDisagreement(MatchDisagreement disagreement);
    Optional<MatchDisagreement> findDisagreement(UUID disagreementId);
    Page<MatchDisagreement> listDisagreements(UUID reportId, PageRequest request);
    @Deprecated(forRemoval = false)
    default List<MatchDisagreement> listDisagreements(UUID reportId) {
        return listDisagreements(reportId, PageRequest.of(null, null, "match-disagreements",
                "report=" + reportId)).items();
    }
    MatchDisagreement updateDisagreement(MatchDisagreement disagreement);

    record ReportWithRevisions(MatchReport report, Instant currentProfileRevision, Instant currentJobRevision) {
        public boolean stale() {
            return currentProfileRevision == null || currentJobRevision == null
                    || report.stale(currentProfileRevision, currentJobRevision);
        }
    }
}

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
    List<MatchReport> listReports(UUID profileId, UUID jobId);
    Optional<ReportWithRevisions> findReportWithRevisions(UUID reportId);
    Page<ReportWithRevisions> listReports(UUID profileId, UUID jobId, PageRequest request);
    MatchReview saveReview(MatchReview review);
    Optional<MatchReview> findReview(UUID reviewId);
    List<MatchReview> listReviews(UUID reportId);
    MatchDisagreement saveDisagreement(MatchDisagreement disagreement);
    Optional<MatchDisagreement> findDisagreement(UUID disagreementId);
    List<MatchDisagreement> listDisagreements(UUID reportId);
    MatchDisagreement updateDisagreement(MatchDisagreement disagreement);

    record ReportWithRevisions(MatchReport report, Instant currentProfileRevision, Instant currentJobRevision) {
        public boolean stale() {
            return currentProfileRevision == null || currentJobRevision == null
                    || report.stale(currentProfileRevision, currentJobRevision);
        }
    }
}

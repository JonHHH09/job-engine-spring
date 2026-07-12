package org.instruct.jobenginespring.application.match.port;

import org.instruct.jobenginespring.domain.match.MatchDisagreement;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.match.MatchReview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchAnalysisRepository {
    MatchReport saveReport(MatchReport report);
    Optional<MatchReport> findReport(UUID reportId);
    List<MatchReport> listReports(UUID profileId, UUID jobId);
    MatchReview saveReview(MatchReview review);
    Optional<MatchReview> findReview(UUID reviewId);
    List<MatchReview> listReviews(UUID reportId);
    MatchDisagreement saveDisagreement(MatchDisagreement disagreement);
    Optional<MatchDisagreement> findDisagreement(UUID disagreementId);
    List<MatchDisagreement> listDisagreements(UUID reportId);
    MatchDisagreement updateDisagreement(MatchDisagreement disagreement);
}

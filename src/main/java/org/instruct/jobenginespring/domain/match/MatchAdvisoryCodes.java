package org.instruct.jobenginespring.domain.match;

import java.util.Set;

/** Closed vocabulary accepted from advisory match reviews. */
public final class MatchAdvisoryCodes {
    public static final Set<String> SUMMARY_CODES = Set.of(
            "review_consistent",
            "score_adjustment",
            "outcome_adjustment",
            "evidence_defect_identified");
    public static final Set<String> EVIDENCE_DEFECT_CODES = Set.of(
            "structured_evidence_missing",
            "structured_evidence_incorrect",
            "requirement_provenance_missing",
            "outcome_calibration_issue");

    private MatchAdvisoryCodes() {
    }

    public static String requireSummaryCode(String value) {
        var code = value == null ? "" : value.trim();
        if (!SUMMARY_CODES.contains(code)) {
            throw new IllegalArgumentException("summary must be an allowed advisory summary code");
        }
        return code;
    }

    public static boolean isEvidenceDefectCode(String value) {
        return EVIDENCE_DEFECT_CODES.contains(value);
    }
}

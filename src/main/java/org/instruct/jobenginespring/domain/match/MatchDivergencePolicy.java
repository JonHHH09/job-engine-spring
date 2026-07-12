package org.instruct.jobenginespring.domain.match;

import java.util.EnumSet;
import java.util.Set;

public enum MatchDivergencePolicy {
    V1("divergence-v1");

    private final String version;

    MatchDivergencePolicy(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }

    public Set<DisagreementReason> reasons(MatchReport report, MatchReview review) {
        var reasons = EnumSet.noneOf(DisagreementReason.class);
        if (Math.abs(report.overallScore() - review.overallScore()) >= 15) reasons.add(DisagreementReason.OVERALL_DELTA);
        if (report.outcome() != review.outcome()) reasons.add(DisagreementReason.OUTCOME_MISMATCH);
        if (report.blockerMismatch() != review.blockerMismatch()) reasons.add(DisagreementReason.BLOCKER_MISMATCH);
        for (var component : review.components()) {
            report.components().stream().filter(c -> c.component() == component.component()).findFirst()
                    .filter(baseline -> Math.abs(baseline.earnedPoints() - component.earnedPoints()) * 100
                            >= component.availablePoints() * 40)
                    .ifPresent(ignored -> reasons.add(DisagreementReason.COMPONENT_DELTA));
        }
        if (review.evidence().stream().anyMatch(MatchEvidence::outcomeChangingDefect)) reasons.add(DisagreementReason.EVIDENCE_DEFECT);
        return Set.copyOf(reasons);
    }
}

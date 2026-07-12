package org.instruct.jobenginespring.domain.match;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public record MatchDisagreement(UUID id, UUID reportId, UUID reviewId, String policyVersion,
                                Set<DisagreementReason> reasons, List<String> evidenceDefectCodes,
                                DisagreementStatus status, String linearIssueId, Instant createdAt, Instant updatedAt) {
    public MatchDisagreement {
        if (id == null || reportId == null || reviewId == null || policyVersion == null || status == null
                || createdAt == null || updatedAt == null)
            throw new NullPointerException("disagreement required fields must not be null");
        policyVersion = MatchPrivacy.label(policyVersion, "policyVersion");
        reasons = reasons == null ? Set.of() : Set.copyOf(reasons);
        if (reasons.isEmpty()) throw new IllegalArgumentException("reasons must not be empty");
        evidenceDefectCodes = evidenceDefectCodes == null ? List.of() : evidenceDefectCodes.stream().sorted().distinct().toList();
        if (evidenceDefectCodes.stream().anyMatch(code -> !MatchAdvisoryCodes.isEvidenceDefectCode(code))) {
            throw new IllegalArgumentException("evidenceDefectCodes must contain only allowed advisory defect codes");
        }
        if (linearIssueId != null && linearIssueId.isBlank()) throw new IllegalArgumentException("linearIssueId must not be blank");
    }

    public UUID fingerprint() {
        return fingerprint(reportId, policyVersion, reasons, evidenceDefectCodes);
    }

    public static UUID fingerprint(UUID reportId, String policyVersion, Set<DisagreementReason> reasons,
                                   List<String> evidenceDefectCodes) {
        var identity = "%s|%s|%s|%s".formatted(reportId, policyVersion,
                reasons.stream().sorted().map(Enum::name).toList(), evidenceDefectCodes.stream().sorted().distinct().toList());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}

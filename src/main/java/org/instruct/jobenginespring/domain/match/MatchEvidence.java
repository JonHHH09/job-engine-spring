package org.instruct.jobenginespring.domain.match;

import java.util.Objects;

public record MatchEvidence(MatchComponent component, EvidenceStatus status, String sourceType,
                            String fact, boolean outcomeChangingDefect) {
    public MatchEvidence {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(status, "status must not be null");
        sourceType = MatchPrivacy.label(sourceType, "sourceType");
        fact = MatchPrivacy.evidenceFact(fact, sourceType);
    }

}

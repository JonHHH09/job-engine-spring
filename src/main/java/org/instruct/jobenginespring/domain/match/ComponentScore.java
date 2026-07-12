package org.instruct.jobenginespring.domain.match;

import java.util.Objects;

public record ComponentScore(MatchComponent component, int earnedPoints, int availablePoints,
                             EvidenceStatus status) {
    public ComponentScore {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (availablePoints != component.availablePoints() || earnedPoints < 0 || earnedPoints > availablePoints) {
            throw new IllegalArgumentException("component score must be within its configured available points");
        }
    }
}

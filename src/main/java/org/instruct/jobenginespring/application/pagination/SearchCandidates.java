package org.instruct.jobenginespring.application.pagination;

import java.util.List;
import java.util.Objects;

public record SearchCandidates<T>(int totalMatches, List<T> items) {
    public SearchCandidates {
        if (totalMatches < -1) {
            throw new IllegalArgumentException("totalMatches must be -1 or non-negative");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (totalMatches >= 0 && items.size() > totalMatches) {
            throw new IllegalArgumentException("items must not exceed totalMatches");
        }
    }
}

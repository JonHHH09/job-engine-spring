package org.instruct.jobenginespring.application.pagination;

import java.util.List;
import java.util.Objects;

public record SearchCandidates<T>(int matchedCount, boolean hasMore, List<T> items) {
    public SearchCandidates {
        if (matchedCount < -1) {
            throw new IllegalArgumentException("matchedCount must be -1 or non-negative");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (matchedCount >= 0 && items.size() > matchedCount) {
            throw new IllegalArgumentException("items must not exceed matchedCount");
        }
    }

    public SearchCandidates(int matchedCount, List<T> items) {
        this(matchedCount, false, items);
    }
}

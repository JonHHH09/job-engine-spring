package org.instruct.jobenginespring.application.pagination;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Page<T>(List<T> items, UUID nextCursor) {
    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}

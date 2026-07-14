package org.instruct.jobenginespring.application.pagination;

import java.util.UUID;

public record PageRequest(int limit, UUID cursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static PageRequest of(Integer limit, UUID cursor) {
        return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, cursor);
    }
}

package org.instruct.jobenginespring.domain.profile;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class ProfileRecordSupport {

    private ProfileRecordSupport() {
    }

    static UUID requireId(UUID id, String fieldName) {
        return Objects.requireNonNull(id, fieldName + " must not be null");
    }

    static Instant requireInstant(Instant instant, String fieldName) {
        return Objects.requireNonNull(instant, fieldName + " must not be null");
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static String normalizeRequiredText(String value, String fieldName) {
        return requireText(value, fieldName).trim().toLowerCase(Locale.ROOT);
    }

    static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

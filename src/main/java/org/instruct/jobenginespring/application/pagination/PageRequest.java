package org.instruct.jobenginespring.application.pagination;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A bounded page request with an authenticated opaque cursor.
 *
 * <p>The HMAC key comes from {@value #CURSOR_SECRET_ENV} when configured. Otherwise a
 * cryptographically random process-local key intentionally expires cursors on restart.
 */
public record PageRequest(int limit, Cursor cursor, String scope, String fingerprint) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    static final String CURSOR_SECRET_ENV = "JOB_ENGINE_CURSOR_HMAC_SECRET";
    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 4_096;
    private static final byte[] HMAC_KEY = cursorKey(System.getenv(CURSOR_SECRET_ENV));

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        scope = requireValue(scope, "scope");
        fingerprint = requireValue(fingerprint, "fingerprint");
        if (cursor != null && (!scope.equals(cursor.scope()) || !fingerprint.equals(cursor.fingerprint()))) {
            throw invalidCursor();
        }
    }

    public static PageRequest of(Integer limit, String cursor, String scope, String filterIdentity) {
        var fingerprint = DigestUtils.sha256Hex(Objects.requireNonNull(filterIdentity, "filterIdentity must not be null"));
        return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, decode(cursor), scope, fingerprint);
    }

    public String nextCursor(Instant snapshotAt, Instant createdAt, UUID id) {
        var payload = String.join("|", VERSION, scope, fingerprint, snapshotAt.toString(), createdAt.toString(), id.toString())
                .getBytes(StandardCharsets.UTF_8);
        return encode(payload) + "." + encode(sign(payload));
    }

    private static Cursor decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        if (encoded.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            var envelope = encoded.split("\\.", -1);
            if (envelope.length != 2) {
                throw invalidCursor();
            }
            var payload = Base64.getUrlDecoder().decode(envelope[0]);
            var suppliedSignature = Base64.getUrlDecoder().decode(envelope[1]);
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
                throw invalidCursor();
            }
            var decoded = new String(payload, StandardCharsets.UTF_8);
            var parts = decoded.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }
            return new Cursor(parts[1], parts[2], Instant.parse(parts[3]), Instant.parse(parts[4]), UUID.fromString(parts[5]));
        } catch (RuntimeException exception) {
            if (exception instanceof ApplicationException applicationException) {
                throw applicationException;
            }
            throw invalidCursor();
        }
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must be a non-blank cursor-safe value");
        }
        return value;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sign(byte[] payload) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, HMAC_KEY).hmac(payload);
    }

    static byte[] cursorKey(String configuredSecret) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            if (configuredSecret.length() < 32) {
                throw new IllegalStateException(CURSOR_SECRET_ENV + " must contain at least 32 characters");
            }
            return configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static ApplicationException invalidCursor() {
        return new ApplicationException(ApplicationErrorCode.VALIDATION_ERROR, "Invalid pagination cursor",
                Map.of("field", "cursor", "reason", "malformed or does not match this request"), null);
    }

    public record Cursor(String scope, String fingerprint, Instant snapshotAt, Instant createdAt, UUID id) {
        public Cursor {
            scope = requireValue(scope, "scope");
            fingerprint = requireValue(fingerprint, "fingerprint");
            Objects.requireNonNull(snapshotAt, "snapshotAt must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }
}

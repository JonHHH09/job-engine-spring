package org.instruct.jobenginespring.application.jobscan;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stateless, process-local signed candidate tokens. Tokens expire after fifteen minutes and become
 * invalid after restart because the signing key is deliberately not persisted.
 */
@Component
public final class ArbeitnowCandidateTokenCodec {
    private static final int VERSION = 1;
    private static final String PROVIDER = "arbeitnow";
    /** 32 KiB payload encodes to at most 43,691 unpadded Base64URL characters; with '.' and a 43-character MAC this is 43,735. */
    private static final int MAX_TOKEN_CHARS = 44_000;
    /** Covers the UTF-8 JSON payload at the scanner's field-specific maxima while retaining a strict aggregate bound. */
    private static final int MAX_PAYLOAD_BYTES = 32 * 1_024;
    private static final int MAX_SLUG_LENGTH = 256;
    private static final int MAX_SHORT_TEXT_LENGTH = 256;
    private static final int MAX_DESCRIPTION_LENGTH = 1_000;
    private static final int MAX_LIST = 20;
    private static final int MAX_LIST_ELEMENT_LENGTH = 128;
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private static final List<String> FIELDS = List.of("v", "provider", "issuedAt", "expiresAt", "slug", "canonicalUrl", "company", "title", "location", "remote", "postedAt", "tags", "jobTypes", "descriptionExcerpt");
    private static final ObjectMapper STRICT_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Clock clock;
    private final byte[] key;
    private final PayloadSerializer payloadSerializer;
    private final MacFactory macFactory;

    public ArbeitnowCandidateTokenCodec() {
        this(Clock.systemUTC(), randomKey());
    }

    public ArbeitnowCandidateTokenCodec(Clock clock, byte[] key) {
        this(clock, key, OBJECT_MAPPER::writeValueAsString, () -> Mac.getInstance("HmacSHA256"));
    }

    ArbeitnowCandidateTokenCodec(Clock clock, byte[] key, PayloadSerializer payloadSerializer, MacFactory macFactory) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("Candidate token key must contain at least 256 bits");
        }
        this.key = key.clone();
        this.payloadSerializer = Objects.requireNonNull(payloadSerializer, "payloadSerializer must not be null");
        this.macFactory = Objects.requireNonNull(macFactory, "macFactory must not be null");
    }

    public static ArbeitnowCandidateTokenCodec processLocal(Clock clock) {
        return new ArbeitnowCandidateTokenCodec(clock, randomKey());
    }

    Instant now() { return clock.instant(); }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public String issue(Candidate candidate) {
        validate(candidate, true);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", VERSION);
            payload.put("provider", candidate.provider());
            payload.put("issuedAt", candidate.issuedAt().getEpochSecond());
            payload.put("expiresAt", candidate.expiresAt().getEpochSecond());
            payload.put("slug", candidate.slug());
            payload.put("canonicalUrl", candidate.canonicalUrl());
            payload.put("company", candidate.company());
            payload.put("title", candidate.title());
            payload.put("location", candidate.location());
            payload.put("remote", candidate.remote());
            payload.put("postedAt", candidate.postedAt() == null ? null : candidate.postedAt().getEpochSecond());
            payload.put("tags", candidate.tags());
            payload.put("jobTypes", candidate.jobTypes());
            payload.put("descriptionExcerpt", candidate.descriptionExcerpt());
            byte[] bytes = payloadSerializer.serialize(payload).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                throw invalid();
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(bytes));
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public Candidate verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_CHARS || token.indexOf('.') <= 0 || token.indexOf('.') != token.lastIndexOf('.')) {
            throw invalid();
        }
        try {
            String[] parts = token.split("\\.", -1);
            byte[] payload = decode(parts[0], MAX_PAYLOAD_BYTES);
            byte[] suppliedMac = decode(parts[1], 64);
            if (suppliedMac.length != 32 || !MessageDigest.isEqual(suppliedMac, hmac(payload))) {
                throw invalid();
            }
            JsonNode root = readStrictTree(payload);
            if (root == null || !root.isObject() || root.size() != FIELDS.size() || !root.propertyNames().containsAll(FIELDS)) {
                throw invalid();
            }
            Candidate candidate = new Candidate(
                    requiredText(root, "provider"), instant(root, "issuedAt", false), instant(root, "expiresAt", false),
                    requiredText(root, "slug"), requiredText(root, "canonicalUrl"), requiredText(root, "company"),
                    requiredText(root, "title"), requiredText(root, "location"), requiredRemote(root),
                    instant(root, "postedAt", true), list(root, "tags"), list(root, "jobTypes"), requiredText(root, "descriptionExcerpt")
            );
            JsonNode version = root.get("v");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != VERSION) {
                throw invalid();
            }
            validate(candidate, false);
            Instant now = clock.instant();
            if (candidate.issuedAt().isAfter(now.plusSeconds(60)) || candidate.expiresAt().isBefore(now)) {
                throw invalid();
            }
            return candidate;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static byte[] decode(String value, int max) {
        if (value.isEmpty()) throw invalid();
        if (value.indexOf('=') >= 0) throw invalid();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw invalid();
        if (decoded.length > max) throw invalid();
        return decoded;
    }

    private static JsonNode readStrictTree(byte[] payload) {
        try (JsonParser parser = STRICT_OBJECT_MAPPER.createParser(payload)) {
            return STRICT_OBJECT_MAPPER.readTree(parser);
        }
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (!node.isString()) throw invalid();
        return node.stringValue();
    }

    private static boolean requiredRemote(JsonNode root) {
        JsonNode node = root.get("remote");
        if (!node.isBoolean()) throw invalid();
        return node.booleanValue();
    }

    private static Instant instant(JsonNode root, String name, boolean nullable) {
        JsonNode node = root.get(name);
        if ((node.isNull() && !nullable) || (!node.isNull() && (!node.isIntegralNumber() || !node.canConvertToLong()))) throw invalid();
        return node.isNull() ? null : Instant.ofEpochSecond(node.longValue());
    }

    private static List<String> list(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (!node.isArray() || node.size() > MAX_LIST) throw invalid();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString()) throw invalid();
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private void validate(Candidate candidate, boolean requireCurrentLifetime) {
        if (candidate == null || !PROVIDER.equals(candidate.provider()) || candidate.issuedAt() == null || candidate.expiresAt() == null
                || candidate.expiresAt().isBefore(candidate.issuedAt()) || candidate.expiresAt().minusSeconds(900).isAfter(candidate.issuedAt())
                || !SLUG.matcher(candidate.slug() == null ? "" : candidate.slug()).matches() || invalidText(candidate.slug(), MAX_SLUG_LENGTH)
                || !canonical(candidate.slug()).equals(candidate.canonicalUrl()) || invalidText(candidate.company(), MAX_SHORT_TEXT_LENGTH) || invalidText(candidate.title(), MAX_SHORT_TEXT_LENGTH)
                || invalidText(candidate.location(), MAX_SHORT_TEXT_LENGTH) || invalidText(candidate.descriptionExcerpt(), MAX_DESCRIPTION_LENGTH)
                || invalidList(candidate.tags()) || invalidList(candidate.jobTypes())) {
            throw invalid();
        }
        if (requireCurrentLifetime && candidate.issuedAt().isAfter(clock.instant().plusSeconds(60))) throw invalid();
    }

    private static boolean invalidText(String value, int maximumLength) {
        return value == null || value.codePointCount(0, value.length()) > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl);
    }
    private static boolean invalidList(List<String> values) {
        return values.size() > MAX_LIST
                || values.stream().anyMatch(value -> invalidText(value, MAX_LIST_ELEMENT_LENGTH));
    }
    static String canonical(String slug) { return "https://arbeitnow.com/view/" + slug; }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = macFactory.create();
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Arbeitnow candidate token"); }

    @FunctionalInterface
    interface PayloadSerializer {
        String serialize(Map<String, Object> payload) throws Exception;
    }

    @FunctionalInterface
    interface MacFactory {
        Mac create() throws Exception;
    }

    public record Candidate(String provider, Instant issuedAt, Instant expiresAt, String slug, String canonicalUrl, String company,
                            String title, String location, boolean remote, Instant postedAt, List<String> tags,
                            List<String> jobTypes, String descriptionExcerpt) {
        public Candidate { tags = tags == null ? List.of() : List.copyOf(tags); jobTypes = jobTypes == null ? List.of() : List.copyOf(jobTypes); }
    }
}

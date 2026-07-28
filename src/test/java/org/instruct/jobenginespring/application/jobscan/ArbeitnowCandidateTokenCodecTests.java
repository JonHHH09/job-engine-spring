package org.instruct.jobenginespring.application.jobscan;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArbeitnowCandidateTokenCodecTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T10:00:00Z"), ZoneOffset.UTC);
    private static final byte[] KEY = new byte[32];

    @Test
    void issuesAndVerifiesOnlyTheStrictCanonicalCandidatePayload() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        ArbeitnowCandidateTokenCodec.Candidate candidate = candidate();

        String token = codec.issue(candidate);

        assertEquals(candidate, codec.verify(token));
    }

    @Test
    void rejectsTamperingExpiryFutureIssueWrongProviderAndRestartKey() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String token = codec.issue(candidate());

        assertThrows(IllegalArgumentException.class, () -> codec.verify(token.substring(0, token.length() - 1) + "A"));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowCandidateTokenCodec(
                Clock.fixed(Instant.parse("2026-07-27T10:16:00Z"), ZoneOffset.UTC), KEY).verify(token));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowCandidateTokenCodec(CLOCK, differentKey()).verify(token));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "other", candidate().issuedAt(), candidate().expiresAt(), candidate().slug(), candidate().canonicalUrl(),
                candidate().company(), candidate().title(), candidate().location(), candidate().remote(), candidate().postedAt(),
                candidate().tags(), candidate().jobTypes(), candidate().descriptionExcerpt())));
    }

    @Test
    void rejectsMalformedOversizedAndCanonicalMismatchedTokensBeforeParsingPersistenceData() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);

        assertThrows(IllegalArgumentException.class, () -> codec.verify("not-a-token"));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("A".repeat(20_000)));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", candidate().issuedAt(), candidate().expiresAt(), "valid-slug", "https://evil.example/view/valid-slug",
                "Acme", "Engineer", "Berlin", true, candidate().postedAt(), List.of("Java"), List.of("full-time"), "Build systems")));
    }

    @Test
    void issuesAndVerifiesWorstCaseUtf8ScannerCandidateWhileRejectingValuesBeyondScannerMaxima() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String fourByteCharacter = "\uD83D\uDE80";
        String slug = "s".repeat(256);
        ArbeitnowCandidateTokenCodec.Candidate maximumCandidate = new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", CLOCK.instant(), CLOCK.instant().plusSeconds(900), slug,
                ArbeitnowCandidateTokenCodec.canonical(slug), fourByteCharacter.repeat(256), fourByteCharacter.repeat(256),
                fourByteCharacter.repeat(256), true, null,
                java.util.Collections.nCopies(20, fourByteCharacter.repeat(128)),
                java.util.Collections.nCopies(20, fourByteCharacter.repeat(128)), fourByteCharacter.repeat(1_000));

        assertEquals(maximumCandidate, codec.verify(codec.issue(maximumCandidate)));
        ArbeitnowCandidateTokenCodec.Candidate oversizedDescription = new ArbeitnowCandidateTokenCodec.Candidate(
                maximumCandidate.provider(), maximumCandidate.issuedAt(), maximumCandidate.expiresAt(), maximumCandidate.slug(),
                maximumCandidate.canonicalUrl(), maximumCandidate.company(), maximumCandidate.title(), maximumCandidate.location(),
                maximumCandidate.remote(), maximumCandidate.postedAt(), maximumCandidate.tags(), maximumCandidate.jobTypes(),
                fourByteCharacter.repeat(1_001));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(oversizedDescription));
    }

    @Test
    void rejectsSignedPayloadWithDuplicateSecurityRelevantField() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);

        String token = signedToken(codec.issue(candidate()), payload -> payload.substring(0, payload.length() - 1)
                + ",\"expiresAt\":" + candidate().expiresAt().getEpochSecond() + "}");

        assertThrows(IllegalArgumentException.class, () -> codec.verify(token));
    }

    @Test
    void rejectsSignedPayloadWithTrailingJsonValue() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);

        String token = signedToken(codec.issue(candidate()), payload -> payload + " []");

        assertThrows(IllegalArgumentException.class, () -> codec.verify(token));
    }

    @Test
    void rejectsInvalidKeysAndEveryPublicCandidateValidationBoundary() {
        assertThrows(NullPointerException.class, () -> new ArbeitnowCandidateTokenCodec(null, KEY));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowCandidateTokenCodec(CLOCK, null));
        assertThrows(IllegalArgumentException.class, () -> new ArbeitnowCandidateTokenCodec(CLOCK, new byte[31]));
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        assertThrows(IllegalArgumentException.class, () -> codec.issue(null));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", CLOCK.instant().plusSeconds(61), CLOCK.instant().plusSeconds(900), "slug",
                ArbeitnowCandidateTokenCodec.canonical("slug"), "Acme", "Engineer", "Berlin", false, null, List.of(), List.of(), "Description")));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", CLOCK.instant(), CLOCK.instant().plusSeconds(901), "slug",
                ArbeitnowCandidateTokenCodec.canonical("slug"), "Acme", "Engineer", "Berlin", false, null, List.of(), List.of(), "Description")));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", CLOCK.instant(), CLOCK.instant().plusSeconds(900), "bad/slug",
                ArbeitnowCandidateTokenCodec.canonical("bad/slug"), "Acme", "Engineer", "Berlin", false, null, List.of(), List.of(), "Description")));
        assertThrows(IllegalArgumentException.class, () -> codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", CLOCK.instant(), CLOCK.instant().plusSeconds(900), "slug",
                ArbeitnowCandidateTokenCodec.canonical("slug"), "Acme\u0000", "Engineer", "Berlin", false, null, List.of(), List.of(), "Description")));
    }

    @Test
    void rejectsSignedPayloadsWithWrongFieldTypesAndNonCanonicalBase64() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());
        for (java.util.function.UnaryOperator<String> mutation : List.<java.util.function.UnaryOperator<String>>of(
                payload -> payload.replace("\"v\":1", "\"v\":true"),
                payload -> payload.replace("\"remote\":true", "\"remote\":\"true\""),
                payload -> payload.replace("\"tags\":[\"Java\",\"Spring\"]", "\"tags\":[1]"),
                payload -> payload.replaceFirst("\"postedAt\":[0-9]+", "\"postedAt\":\"bad\""),
                payload -> payload.substring(0, payload.length() - 1) + ",\"extra\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued, mutation)));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.verify(issued.replace('.', '=')));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("." + issued));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(issued + ".extra"));
    }

    @Test
    void rejectsSignedPayloadsWithExplicitNullListFieldsBeforeCandidateNormalization() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());

        assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued,
                value -> value.replace("\"tags\":[\"Java\",\"Spring\"]", "\"tags\":null"))));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued,
                value -> value.replace("\"jobTypes\":[\"full-time\"]", "\"jobTypes\":null"))));
    }

    @Test
    void sanitizesPayloadSerializationAndMacCreationFailures() {
        ArbeitnowCandidateTokenCodec serializationFailure = new ArbeitnowCandidateTokenCodec(CLOCK, KEY,
                _ -> { throw new Exception("serialization failure"); },
                () -> Mac.getInstance("HmacSHA256"));
        String issued = new ArbeitnowCandidateTokenCodec(CLOCK, KEY).issue(candidate());
        ArbeitnowCandidateTokenCodec macFailure = new ArbeitnowCandidateTokenCodec(CLOCK, KEY,
                new ObjectMapper()::writeValueAsString, () -> { throw new Exception("mac failure"); });

        assertThrows(IllegalArgumentException.class, () -> serializationFailure.issue(candidate()));
        assertThrows(IllegalArgumentException.class, () -> macFailure.verify(issued));
    }

    @Test
    void sanitizesOversizedSerializedPayload() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY,
                _ -> "x".repeat(32 * 1_024 + 1), () -> Mac.getInstance("HmacSHA256"));

        assertThrows(IllegalArgumentException.class, () -> codec.issue(candidate()));
    }

    @Test
    void rejectsEveryRemainingSignedStructuralBoundary() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());
        List<java.util.function.UnaryOperator<String>> mutations = List.of(
                payload -> "[]",
                payload -> payload.replace("\"v\":1", "\"v\":2"),
                payload -> payload.replace("\"provider\":\"arbeitnow\"", "\"provider\":1"),
                payload -> payload.replace("\"issuedAt\":" + candidate().issuedAt().getEpochSecond(), "\"issuedAt\":null"),
                payload -> payload.replace("\"expiresAt\":" + candidate().expiresAt().getEpochSecond(), "\"expiresAt\":\"bad\""),
                payload -> payload.replace("\"tags\":[\"Java\",\"Spring\"]", "\"tags\":[" + "\"Java\",".repeat(20) + "\"Java\"]"),
                payload -> payload.replace("\"slug\":\"platform-engineer-42\"", "\"slug\":\"\""),
                payload -> payload.replace("\"descriptionExcerpt\":\"Build systems\"", "\"descriptionExcerpt\":\"x\\u0000\"")
        );
        for (int index = 0; index < mutations.size(); index++) {
            java.util.function.UnaryOperator<String> mutation = mutations.get(index);
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued, mutation)), "mutation " + index);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.verify("AA." + issued.substring(issued.indexOf('.') + 1)));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("A".repeat(44_001) + ".x"));
    }

    @Test
    void rejectsEmptySignatureAndSignedLifetimeFieldAndCollectionAdversaries() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());
        String payload = issued.substring(0, issued.indexOf('.'));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(payload + "."));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(payload + ".AA"));
        for (java.util.function.UnaryOperator<String> mutation : List.<java.util.function.UnaryOperator<String>>of(
                value -> value.replace("\"issuedAt\":" + candidate().issuedAt().getEpochSecond(), "\"issuedAt\":" + CLOCK.instant().plusSeconds(61).getEpochSecond()),
                value -> value.replace("\"expiresAt\":" + candidate().expiresAt().getEpochSecond(), "\"expiresAt\":" + CLOCK.instant().minusSeconds(1).getEpochSecond()),
                value -> value.replace("\"expiresAt\":" + candidate().expiresAt().getEpochSecond(), "\"expiresAt\":" + candidate().issuedAt().minusSeconds(1).getEpochSecond()),
                value -> value.replace("\"postedAt\":" + candidate().postedAt().getEpochSecond(), "\"postedAt\":9223372036854775807"),
                value -> value.replace("\"jobTypes\":[\"full-time\"]", "\"jobTypes\":[null]"),
                value -> value.replace("\"company\":\"Acme\"", "\"company\":\"" + "x".repeat(257) + "\""),
                value -> value.replace("\"v\":1", "\"v\":null"))) {
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued, mutation)));
        }
    }

    @Test
    void rejectsRemainingTokenEncodingAndSignedSchemaVariants() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());
        String payload = issued.substring(0, issued.indexOf('.'));
        String mac = issued.substring(issued.indexOf('.') + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.verify(null));
        assertThrows(IllegalArgumentException.class, () -> codec.verify(payload + ".="));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("AB." + mac));
        assertThrows(IllegalArgumentException.class, () -> codec.verify("A".repeat(43_692) + ".AA"));
        for (java.util.function.UnaryOperator<String> mutation : List.<java.util.function.UnaryOperator<String>>of(
                value -> value.replace("\"provider\":\"arbeitnow\"", "\"missingProvider\":\"arbeitnow\""),
                value -> value.replace("\"remote\":true", "\"remote\":null"),
                value -> value.replace("\"tags\":[\"Java\",\"Spring\"]", "\"tags\":{}"),
                value -> value.replace("\"v\":1", "\"v\":2147483648"),
                value -> value.replace("\"slug\":\"platform-engineer-42\"", "\"slug\":\"" + "s".repeat(257) + "\""),
                value -> value.replace("\"tags\":[\"Java\",\"Spring\"]", "\"tags\":[\"x\\u0000\"]"))) {
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued, mutation)));
        }
    }

    @Test
    void rejectsSignedCanonicalUrlAndJobTypeListViolationsAfterAuthenticatingThePayload() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        String issued = codec.issue(candidate());

        for (java.util.function.UnaryOperator<String> mutation : List.<java.util.function.UnaryOperator<String>>of(
                value -> value.replace("\"canonicalUrl\":\"https://arbeitnow.com/view/platform-engineer-42\"", "\"canonicalUrl\":\"https://evil.example/view/platform-engineer-42\""),
                value -> value.replace("\"jobTypes\":[\"full-time\"]", "\"jobTypes\":[" + "\"full-time\",".repeat(20) + "\"full-time\"]"))) {
            assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(issued, mutation)));
        }
    }

    @Test
    void validatesIssueOnlyCandidateBoundariesAndNormalizesNullLists() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        ArbeitnowCandidateTokenCodec.Candidate base = candidate();
        assertEquals(new ArbeitnowCandidateTokenCodec.Candidate(
                base.provider(), base.issuedAt(), base.expiresAt(), base.slug(), base.canonicalUrl(), base.company(), base.title(),
                base.location(), base.remote(), base.postedAt(), List.of(), List.of(), base.descriptionExcerpt()),
                codec.verify(codec.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                        base.provider(), base.issuedAt(), base.expiresAt(), base.slug(), base.canonicalUrl(), base.company(), base.title(),
                        base.location(), base.remote(), base.postedAt(), null, null, base.descriptionExcerpt()))));
        for (ArbeitnowCandidateTokenCodec.Candidate invalid : List.of(
                new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", null, base.expiresAt(), base.slug(), base.canonicalUrl(), base.company(), base.title(), base.location(), base.remote(), base.postedAt(), List.of(), List.of(), base.descriptionExcerpt()),
                new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", base.issuedAt(), base.expiresAt(), null, base.canonicalUrl(), base.company(), base.title(), base.location(), base.remote(), base.postedAt(), List.of(), List.of(), base.descriptionExcerpt()),
                new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", base.issuedAt(), base.expiresAt(), base.slug(), base.canonicalUrl(), null, base.title(), base.location(), base.remote(), base.postedAt(), List.of(), List.of(), base.descriptionExcerpt()),
                new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", base.issuedAt(), base.expiresAt(), base.slug(), base.canonicalUrl(), base.company(), base.title(), null, base.remote(), base.postedAt(), List.of(), List.of(), base.descriptionExcerpt()),
                new ArbeitnowCandidateTokenCodec.Candidate("arbeitnow", base.issuedAt(), base.expiresAt(), base.slug(), base.canonicalUrl(), base.company(), base.title(), base.location(), base.remote(), base.postedAt(), java.util.Collections.nCopies(21, "x"), List.of(), base.descriptionExcerpt()))) {
            assertThrows(IllegalArgumentException.class, () -> codec.issue(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.verify(signedToken(codec.issue(base), value -> " ")));
    }

    @Test
    void rejectsRemainingInstantValidationAndListElementAdversariesWithoutLeakingPayloadDetails() {
        ArbeitnowCandidateTokenCodec codec = new ArbeitnowCandidateTokenCodec(CLOCK, KEY);
        ArbeitnowCandidateTokenCodec.Candidate base = candidate();
        String issued = codec.issue(base);

        for (java.util.function.UnaryOperator<String> mutation : List.<java.util.function.UnaryOperator<String>>of(
                value -> value.replace("\"issuedAt\":" + base.issuedAt().getEpochSecond(), "\"issuedAt\":9223372036854775808"),
                value -> value.replace("\"title\":\"Platform Engineer\"", "\"title\":\"x\\u0000\""),
                value -> value.replace("\"jobTypes\":[\"full-time\"]", "\"jobTypes\":[\"x\\u0000\"]"))) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> codec.verify(signedToken(issued, mutation)));
            assertEquals("Invalid Arbeitnow candidate token", exception.getMessage());
        }
        ArbeitnowCandidateTokenCodec.Candidate missingExpiry = new ArbeitnowCandidateTokenCodec.Candidate(
                base.provider(), base.issuedAt(), null, base.slug(), base.canonicalUrl(), base.company(), base.title(),
                base.location(), base.remote(), base.postedAt(), base.tags(), base.jobTypes(), base.descriptionExcerpt());
        assertEquals("Invalid Arbeitnow candidate token", assertThrows(IllegalArgumentException.class,
                () -> codec.issue(missingExpiry)).getMessage());
    }

    private static ArbeitnowCandidateTokenCodec.Candidate candidate() {
        return new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", Instant.parse("2026-07-27T10:00:00Z"), Instant.parse("2026-07-27T10:15:00:00Z".replace(":00:00Z", ":00Z")),
                "platform-engineer-42", "https://arbeitnow.com/view/platform-engineer-42", "Acme", "Platform Engineer",
                "Berlin", true, Instant.parse("2026-07-20T00:00:00Z"), List.of("Java", "Spring"), List.of("full-time"), "Build systems");
    }

    private static byte[] differentKey() {
        byte[] key = KEY.clone();
        key[0] = 1;
        return key;
    }

    private static String signedToken(String issuedToken, java.util.function.UnaryOperator<String> change) {
        String payloadPart = issuedToken.substring(0, issuedToken.indexOf('.'));
        byte[] payload = change.apply(new String(Base64.getUrlDecoder().decode(payloadPart), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    private static byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

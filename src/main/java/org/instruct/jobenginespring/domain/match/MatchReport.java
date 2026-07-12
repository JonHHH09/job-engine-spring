package org.instruct.jobenginespring.domain.match;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MatchReport(UUID id, UUID profileId, UUID jobId, Instant profileRevision, Instant jobRevision,
                          String algorithmVersion, int overallScore, int confidence, MatchOutcome outcome,
                          boolean blockerMismatch, List<ComponentScore> components,
                          List<MatchEvidence> evidence, Instant createdAt) {
    public MatchReport {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(profileRevision, "profileRevision must not be null");
        Objects.requireNonNull(jobRevision, "jobRevision must not be null");
        if (algorithmVersion == null || algorithmVersion.isBlank()) throw new IllegalArgumentException("algorithmVersion must not be blank");
        if (overallScore < 0 || overallScore > 100 || confidence < 0 || confidence > 100) throw new IllegalArgumentException("score and confidence must be 0-100");
        Objects.requireNonNull(outcome, "outcome must not be null");
        components = components == null ? List.of() : List.copyOf(components);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static MatchReport create(UUID profileId, UUID jobId, Instant profileRevision, Instant jobRevision,
                                     String version, List<ComponentScore> components, List<MatchEvidence> evidence,
                                     Instant createdAt) {
        var safeComponents = List.copyOf(components);
        int earned = safeComponents.stream().mapToInt(ComponentScore::earnedPoints).sum();
        int known = safeComponents.stream().filter(c -> c.status() != EvidenceStatus.UNKNOWN)
                .mapToInt(ComponentScore::availablePoints).sum();
        int score = known == 0 ? 0 : (int) Math.round(earned * 100.0 / known);
        var outcome = known == 0 ? MatchOutcome.INSUFFICIENT_EVIDENCE
                : score >= 75 ? MatchOutcome.STRONG_MATCH : score >= 45 ? MatchOutcome.PARTIAL_MATCH : MatchOutcome.WEAK_MATCH;
        var identity = "%s|%s|%s|%s|%s".formatted(version, profileId, profileRevision, jobId, jobRevision);
        return new MatchReport(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), profileId, jobId, profileRevision, jobRevision, version,
                score, known, outcome, false, safeComponents, evidence, createdAt);
    }

    public ComponentScore component(MatchComponent component) {
        return components.stream().filter(score -> score.component() == component).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing component " + component));
    }

    public boolean stale(Instant currentProfileRevision, Instant currentJobRevision) {
        return !profileRevision.equals(currentProfileRevision) || !jobRevision.equals(currentJobRevision);
    }
}

package org.instruct.jobenginespring.domain.match;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public record MatchReview(UUID id, UUID reportId, String reviewer, String model, String reviewVersion,
                          int overallScore, MatchOutcome outcome, boolean blockerMismatch,
                          List<ComponentScore> components, List<MatchEvidence> evidence,
                          String summary, Instant createdAt) {
    public MatchReview {
        Objects.requireNonNull(id); Objects.requireNonNull(reportId); Objects.requireNonNull(outcome); Objects.requireNonNull(createdAt);
        reviewer = MatchPrivacy.label(reviewer, "reviewer");
        model = MatchPrivacy.label(model, "model");
        reviewVersion = MatchPrivacy.label(reviewVersion, "reviewVersion");
        if (overallScore < 0 || overallScore > 100) throw new IllegalArgumentException("overallScore must be 0-100");
        components = components == null ? List.of() : List.copyOf(components);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        summary = MatchAdvisoryCodes.requireSummaryCode(summary);
    }

    public UUID fingerprint() {
        var canonicalComponents = components.stream().sorted(Comparator.comparing(score -> score.component().name()))
                .map(score -> encode(score.component().name()) + encode(score.earnedPoints())
                        + encode(score.availablePoints()) + encode(score.status().name())).toList();
        var canonicalEvidence = evidence.stream().sorted(Comparator
                        .comparing((MatchEvidence item) -> item.component().name())
                        .thenComparing(item -> item.status().name()).thenComparing(MatchEvidence::sourceType)
                        .thenComparing(MatchEvidence::fact).thenComparing(MatchEvidence::outcomeChangingDefect))
                .map(item -> encode(item.component().name()) + encode(item.status().name()) + encode(item.sourceType())
                        + encode(item.fact()) + encode(item.outcomeChangingDefect())).toList();
        var identity = encode(reportId) + encode(reviewer) + encode(model) + encode(reviewVersion)
                + encode(overallScore) + encode(outcome.name()) + encode(blockerMismatch)
                + encode(canonicalComponents) + encode(canonicalEvidence) + encode(summary);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(Object value) {
        var text = String.valueOf(value);
        return text.length() + ":" + text;
    }
}

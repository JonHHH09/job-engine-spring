package org.instruct.jobenginespring.domain.match;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DeterministicMatchScorer {
    public static final String ALGORITHM_VERSION = "deterministic-v1";
    private static final Map<String, String> ALIASES = Map.of(
            "postgres", "postgresql", "k8s", "kubernetes", "spring boot", "spring");

    public MatchReport score(ProfileAggregate profile, JobAggregate job, Instant now) {
        var evidence = new ArrayList<MatchEvidence>();
        var components = new ArrayList<ComponentScore>();
        components.add(scoreTechnical(profile, job, evidence));
        components.add(unknownOrExperience(profile, job, evidence));
        components.add(textComponent(MatchComponent.DOMAIN, job.job().description(), profile, evidence));
        components.add(textComponent(MatchComponent.DELIVERY, job.job().description(), profile, evidence));
        components.add(new ComponentScore(MatchComponent.HARD_REQUIREMENTS, 0, 10, EvidenceStatus.UNKNOWN));
        return MatchReport.create(profile.profile().id(), job.job().id(), profile.profile().updatedAt(),
                job.job().updatedAt(), ALGORITHM_VERSION, components, evidence, now);
    }

    private ComponentScore scoreTechnical(ProfileAggregate profile, JobAggregate job, List<MatchEvidence> evidence) {
        if (job.skills().isEmpty()) return new ComponentScore(MatchComponent.TECHNICAL, 0, 40, EvidenceStatus.UNKNOWN);
        Set<String> profileSkills = profile.skills().stream().map(s -> canonical(s.normalizedSkill())).collect(java.util.stream.Collectors.toSet());
        long matches = job.skills().stream().filter(skill -> profileSkills.contains(canonical(skill.normalizedSkill()))).count();
        for (var skill : job.skills()) {
            var matched = profileSkills.contains(canonical(skill.normalizedSkill()));
            evidence.add(new MatchEvidence(MatchComponent.TECHNICAL, matched ? EvidenceStatus.MATCH : EvidenceStatus.MISMATCH,
                    "normalized_skill", canonical(skill.normalizedSkill()), false));
        }
        int points = (int) (matches * 40 / job.skills().size());
        return new ComponentScore(MatchComponent.TECHNICAL, points, 40,
                matches == job.skills().size() ? EvidenceStatus.MATCH : matches == 0 ? EvidenceStatus.MISMATCH : EvidenceStatus.PARTIAL);
    }

    private ComponentScore unknownOrExperience(ProfileAggregate profile, JobAggregate job, List<MatchEvidence> evidence) {
        if ((job.job().experienceRequirement() == null || job.job().experienceRequirement().isBlank())
                && (job.job().seniority() == null || job.job().seniority().isBlank()))
            return new ComponentScore(MatchComponent.EXPERIENCE_SENIORITY, 0, 25, EvidenceStatus.UNKNOWN);
        if (profile.experiences().isEmpty()) return new ComponentScore(MatchComponent.EXPERIENCE_SENIORITY, 0, 25, EvidenceStatus.UNKNOWN);
        evidence.add(new MatchEvidence(MatchComponent.EXPERIENCE_SENIORITY, EvidenceStatus.PARTIAL,
                "experience_dates", "profile contains dated experience", false));
        return new ComponentScore(MatchComponent.EXPERIENCE_SENIORITY, 13, 25, EvidenceStatus.PARTIAL);
    }

    private ComponentScore textComponent(MatchComponent component, String requirement, ProfileAggregate profile,
                                         List<MatchEvidence> evidence) {
        if (requirement == null || requirement.isBlank()) return new ComponentScore(component, 0, component.availablePoints(), EvidenceStatus.UNKNOWN);
        var profileText = profile.experiences().stream().map(e -> String.valueOf(e.description())).collect(java.util.stream.Collectors.joining(" "));
        if (profileText.isBlank()) return new ComponentScore(component, 0, component.availablePoints(), EvidenceStatus.UNKNOWN);
        return new ComponentScore(component, 0, component.availablePoints(), EvidenceStatus.UNKNOWN);
    }

    private static String canonical(String value) {
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(normalized, normalized);
    }
}

package org.instruct.jobenginespring.domain.match;

import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DeterministicMatchScorerTests {
    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void scoresKnownSkillsDeterministicallyAndTreatsMissingEvidenceAsUnknown() {
        var scorer = new DeterministicMatchScorer();
        var profile = profile(List.of("Java", "Spring"), List.of());
        var job = job(List.of("java", "kubernetes"), null, null, "Build software");

        var first = scorer.score(profile, job, NOW);
        var second = scorer.score(profile, job, NOW);

        assertEquals(first, second);
        assertEquals(20, first.component(MatchComponent.TECHNICAL).earnedPoints());
        assertEquals(50, first.overallScore());
        assertEquals(40, first.confidence());
        assertEquals(MatchOutcome.PARTIAL_MATCH, first.outcome());
        assertEquals(EvidenceStatus.UNKNOWN, first.component(MatchComponent.EXPERIENCE_SENIORITY).status());
        assertEquals(EvidenceStatus.UNKNOWN, first.component(MatchComponent.DOMAIN).status());
        assertTrue(first.confidence() < 100);
        assertFalse(first.blockerMismatch());
        assertEquals("deterministic-v1", first.algorithmVersion());
    }

    @Test
    void aliasesMatchWithoutInventingEvidenceAndRequiredSkillIsNotBlockerProvenance() {
        var report = new DeterministicMatchScorer().score(
                profile(List.of("PostgreSQL", "K8s"), List.of()),
                job(List.of("postgres", "kubernetes"), null, null, "Operate a platform"),
                NOW
        );

        assertEquals(40, report.component(MatchComponent.TECHNICAL).earnedPoints());
        assertEquals(MatchOutcome.STRONG_MATCH, report.outcome());
        assertEquals(40, report.confidence());
        assertFalse(report.blockerMismatch());
        assertTrue(report.evidence().stream().noneMatch(MatchEvidence::outcomeChangingDefect));
    }

    @Test
    void evidenceAndComponentsAreDefensivelyCopiedAndScoresAreValidated() {
        var evidence = new ArrayList<>(List.of(new MatchEvidence(
                MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "profile_skill", "java", false)));
        var components = new ArrayList<>(List.of(new ComponentScore(
                MatchComponent.TECHNICAL, 40, 40, EvidenceStatus.MATCH)));
        var report = MatchReport.create(PROFILE_ID, JOB_ID, NOW, NOW, "v1", components, evidence, NOW);

        evidence.clear();
        components.clear();

        assertEquals(1, report.evidence().size());
        assertEquals(1, report.components().size());
        assertThrows(UnsupportedOperationException.class, () -> report.evidence().add(report.evidence().getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentScore(MatchComponent.TECHNICAL, 41, 40, EvidenceStatus.MATCH));
    }

    @Test
    void divergencePolicyUsesEveryV1Trigger() {
        var baseline = completeReport(80, MatchOutcome.STRONG_MATCH, false);
        var review = new MatchReview(UUID.randomUUID(), baseline.id(), "reviewer", "model", "review-v1",
                64, MatchOutcome.PARTIAL_MATCH, true,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 20, 40, EvidenceStatus.PARTIAL)),
                List.of(), "score_adjustment", NOW);

        var reasons = MatchDivergencePolicy.V1.reasons(baseline, review);

        assertTrue(reasons.contains(DisagreementReason.OVERALL_DELTA));
        assertTrue(reasons.contains(DisagreementReason.OUTCOME_MISMATCH));
        assertTrue(reasons.contains(DisagreementReason.BLOCKER_MISMATCH));
        assertTrue(reasons.contains(DisagreementReason.COMPONENT_DELTA));
    }

    @Test
    void rejectsSensitiveAdvisoryPayloadsAndPrivateUrlVariants() {
        var report = completeReport(80, MatchOutcome.STRONG_MATCH, false);
        assertThrows(IllegalArgumentException.class, () -> new MatchReview(UUID.randomUUID(), report.id(),
                "candidate@example.test", "model", "v1", 80, report.outcome(), false, List.of(), List.of(), "", NOW));
        assertThrows(IllegalArgumentException.class, () -> new MatchReview(UUID.randomUUID(), report.id(),
                "reviewer", "model", "v1", 80, report.outcome(), false, List.of(), List.of(),
                "raw resume text follows", NOW));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvidence(MatchComponent.TECHNICAL,
                EvidenceStatus.MATCH, "normalized_skill", "http://127.0.0.1/private", false));
        assertThrows(IllegalArgumentException.class, () -> new MatchEvidence(MatchComponent.TECHNICAL,
                EvidenceStatus.MATCH, "prompt", "java", false));
    }

    @Test
    void reviewFingerprintIsCanonicalAcrossReorderedInputs() {
        var report = completeReport(80, MatchOutcome.STRONG_MATCH, false);
        var technical = new ComponentScore(MatchComponent.TECHNICAL, 30, 40, EvidenceStatus.PARTIAL);
        var delivery = new ComponentScore(MatchComponent.DELIVERY, 5, 10, EvidenceStatus.PARTIAL);
        var java = new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "normalized_skill", "java", false);
        var deliveryFact = new MatchEvidence(MatchComponent.DELIVERY, EvidenceStatus.PARTIAL, "experience_fact", "delivered systems", false);
        var first = new MatchReview(UUID.randomUUID(), report.id(), "reviewer", "model", "v1", 65,
                MatchOutcome.PARTIAL_MATCH, false, List.of(technical, delivery), List.of(java, deliveryFact), "score_adjustment", NOW);
        var reordered = new MatchReview(UUID.randomUUID(), report.id(), "reviewer", "model", "v1", 65,
                MatchOutcome.PARTIAL_MATCH, false, List.of(delivery, technical), List.of(deliveryFact, java), "score_adjustment", NOW.plusSeconds(5));
        assertEquals(first.fingerprint(), reordered.fingerprint());
    }

    @Test
    void scorerCoversEmptyMismatchExperienceAndBlankTextBranches() {
        var scorer = new DeterministicMatchScorer();
        var empty = scorer.score(profile(List.of(), List.of()), job(List.of(), null, null, "safe"), NOW);
        assertEquals(EvidenceStatus.UNKNOWN, empty.component(MatchComponent.TECHNICAL).status());
        assertEquals(MatchOutcome.INSUFFICIENT_EVIDENCE, empty.outcome());

        var mismatch = scorer.score(profile(List.of("java"), List.of()),
                job(List.of("rust"), "three years", "senior", "safe"), NOW);
        assertEquals(EvidenceStatus.MISMATCH, mismatch.component(MatchComponent.TECHNICAL).status());
        assertEquals(EvidenceStatus.UNKNOWN, mismatch.component(MatchComponent.EXPERIENCE_SENIORITY).status());

        var experience = new Experience(UUID.randomUUID(), PROFILE_ID, "Company", "Engineer", null,
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1), "Delivered systems", 0, NOW);
        var partial = scorer.score(profile(List.of("Java", "Spring"), List.of(experience)),
                job(List.of("java", "rust"), null, "senior", "Delivery platform"), NOW);
        assertEquals(EvidenceStatus.PARTIAL, partial.component(MatchComponent.TECHNICAL).status());
        assertEquals(13, partial.component(MatchComponent.EXPERIENCE_SENIORITY).earnedPoints());
        assertTrue(partial.evidence().stream().anyMatch(e -> e.component() == MatchComponent.EXPERIENCE_SENIORITY));
    }

    @Test
    void domainRecordsValidateEveryBoundaryAndDefaultNullableCollections() {
        assertThrows(NullPointerException.class, () -> new ComponentScore(null, 0, 40, EvidenceStatus.UNKNOWN));
        assertThrows(NullPointerException.class, () -> new ComponentScore(MatchComponent.TECHNICAL, 0, 40, null));
        assertThrows(IllegalArgumentException.class, () -> new ComponentScore(MatchComponent.TECHNICAL, -1, 40, EvidenceStatus.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new ComponentScore(MatchComponent.TECHNICAL, 0, 39, EvidenceStatus.UNKNOWN));

        var report = new MatchReport(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, 0,
                MatchOutcome.INSUFFICIENT_EVIDENCE, false, null, null, NOW);
        assertEquals(List.of(), report.components());
        assertEquals(List.of(), report.evidence());
        assertThrows(IllegalArgumentException.class, () -> report.component(MatchComponent.TECHNICAL));
        assertFalse(report.stale(NOW, NOW));
        assertTrue(report.stale(NOW.plusSeconds(1), NOW));
        assertTrue(report.stale(NOW, NOW.plusSeconds(1)));
        assertReportRejected(null, PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), null, JOB_ID, NOW, NOW, "v1", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, null, NOW, NOW, "v1", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, null, NOW, "v1", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, null, "v1", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, null, 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, " ", 0, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", -1, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 101, 0, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, -1, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, 101, MatchOutcome.WEAK_MATCH, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, 0, null, NOW);
        assertReportRejected(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", 0, 0, MatchOutcome.WEAK_MATCH, null);
    }

    @Test
    void privacyValidationCoversNullBlankLengthNewlineNetworkAndCredentialSignals() throws ReflectiveOperationException {
        assertThrows(IllegalArgumentException.class, () -> evidence(null, "fact"));
        assertThrows(IllegalArgumentException.class, () -> evidence("bad/label", "fact"));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", ""));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", "x".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", "line one\nline two"));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", "line one\rline two"));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", "secret value"));
        assertThrows(IllegalArgumentException.class, () -> evidence("source", "10.1.2.3"));
        assertEquals("safe fact", evidence(" source ", " safe fact ").fact());

        var review = new MatchReview(UUID.randomUUID(), UUID.randomUUID(), "human", "model", "v1", 0,
                MatchOutcome.WEAK_MATCH, false, null, null, "review_consistent", NOW);
        assertEquals(List.of(), review.components());
        assertEquals(List.of(), review.evidence());
        assertEquals("review_consistent", review.summary());
        assertThrows(IllegalArgumentException.class, () -> reviewWithScore(-1));
        assertThrows(IllegalArgumentException.class, () -> reviewWithScore(101));
        assertThrows(NullPointerException.class, () -> new MatchReview(null, UUID.randomUUID(), "human", "model", "v1", 0,
                MatchOutcome.WEAK_MATCH, false, List.of(), List.of(), "", NOW));
        assertThrows(IllegalArgumentException.class, () -> MatchAdvisoryCodes.requireSummaryCode("arbitrary_summary"));
        assertThrows(IllegalArgumentException.class, () -> MatchAdvisoryCodes.requireSummaryCode(null));
        assertEquals("review_consistent", MatchAdvisoryCodes.requireSummaryCode(" review_consistent "));
        var constructor = MatchAdvisoryCodes.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance() instanceof MatchAdvisoryCodes);
    }

    @Test
    void reportOutcomesAndDivergenceNonTriggersAreDeterministic() {
        assertEquals(MatchOutcome.STRONG_MATCH, createdWithScore(75).outcome());
        assertEquals(MatchOutcome.PARTIAL_MATCH, createdWithScore(45).outcome());
        assertEquals(MatchOutcome.WEAK_MATCH, createdWithScore(44).outcome());
        var baseline = completeReport(80, MatchOutcome.STRONG_MATCH, false);
        var matching = new MatchReview(UUID.randomUUID(), baseline.id(), "human", "model", "v1", 79,
                MatchOutcome.STRONG_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 39, 40, EvidenceStatus.MATCH)),
                List.of(new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "normalized_skill", "java", false)),
                "review_consistent", NOW);
        assertEquals(java.util.Set.of(), MatchDivergencePolicy.V1.reasons(baseline, matching));
        var defect = new MatchReview(UUID.randomUUID(), baseline.id(), "human", "model", "v1", 80,
                MatchOutcome.STRONG_MATCH, false, List.of(),
                List.of(new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MISMATCH, "normalized_skill", "java", true)),
                "evidence_defect_identified", NOW);
        assertEquals(java.util.Set.of(DisagreementReason.EVIDENCE_DEFECT), MatchDivergencePolicy.V1.reasons(baseline, defect));
    }

    @Test
    void disagreementValidationAndFingerprintAreStable() {
        var reportId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();
        var reasons = java.util.Set.of(DisagreementReason.OUTCOME_MISMATCH, DisagreementReason.OVERALL_DELTA);
        var first = MatchDisagreement.fingerprint(reportId, "divergence-v1", reasons, List.of());
        var value = new MatchDisagreement(first, reportId, reviewId, "divergence-v1", reasons, List.of(),
                DisagreementStatus.OPEN, null, NOW, NOW);
        assertEquals(first, value.fingerprint());
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(null, reportId, reviewId, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), null, reviewId, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, null, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, null,
                reasons, List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                reasons, List.of(), null, null, NOW, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, null, null, NOW));
        assertThrows(NullPointerException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, null, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                null, List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                java.util.Set.of(), List.of(), DisagreementStatus.OPEN, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                reasons, List.of(), DisagreementStatus.OPEN, " ", NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                reasons, List.of("arbitrary defect"), DisagreementStatus.OPEN, null, NOW, NOW));

        var canonicalDefects = new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1", reasons,
                List.of("structured_evidence_missing", "outcome_calibration_issue", "structured_evidence_missing"),
                DisagreementStatus.OPEN, null, NOW, NOW);
        assertEquals(List.of("outcome_calibration_issue", "structured_evidence_missing"), canonicalDefects.evidenceDefectCodes());
        assertEquals(List.of(), new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1", reasons,
                null, DisagreementStatus.OPEN, null, NOW, NOW).evidenceDefectCodes());

        assertEquals(first, MatchDisagreement.fingerprint(reportId, "divergence-v1",
                java.util.Set.of(DisagreementReason.OVERALL_DELTA, DisagreementReason.OUTCOME_MISMATCH), List.of()));
        assertNotEquals(first, MatchDisagreement.fingerprint(reportId, "divergence-v2", reasons, List.of()));
    }

    @Test
    void scorerTreatsNullAndBlankJobDescriptionsAsUnknownDefensively() {
        var profile = profile(List.of(), List.of());
        var aggregate = mock(JobAggregate.class, RETURNS_DEEP_STUBS);
        when(aggregate.job().id()).thenReturn(JOB_ID);
        when(aggregate.job().updatedAt()).thenReturn(NOW);
        when(aggregate.skills()).thenReturn(List.of());
        when(aggregate.job().experienceRequirement()).thenReturn(null);
        when(aggregate.job().seniority()).thenReturn(null);
        when(aggregate.job().description()).thenReturn(null, " ");

        var scorer = new DeterministicMatchScorer();
        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW).component(MatchComponent.DOMAIN).status());
        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW).component(MatchComponent.DOMAIN).status());
    }

    @Test
    void scorerCoversBlankExperienceAndSeniorityCombinations() {
        var profile = profile(List.of(), List.of());
        var aggregate = mock(JobAggregate.class, RETURNS_DEEP_STUBS);
        when(aggregate.job().id()).thenReturn(JOB_ID);
        when(aggregate.job().updatedAt()).thenReturn(NOW);
        when(aggregate.job().description()).thenReturn("safe");
        when(aggregate.skills()).thenReturn(List.of());
        when(aggregate.job().experienceRequirement()).thenReturn(" ", " ", null, "known");
        when(aggregate.job().seniority()).thenReturn(null, " ", " ", " ");
        var scorer = new DeterministicMatchScorer();

        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW)
                .component(MatchComponent.EXPERIENCE_SENIORITY).status());
        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW)
                .component(MatchComponent.EXPERIENCE_SENIORITY).status());
        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW)
                .component(MatchComponent.EXPERIENCE_SENIORITY).status());
        assertEquals(EvidenceStatus.UNKNOWN, scorer.score(profile, aggregate, NOW)
                .component(MatchComponent.EXPERIENCE_SENIORITY).status());
    }

    @Test
    void fingerprintDistinguishesOnlyOutcomeChangingEvidenceFlag() {
        var reportId = UUID.randomUUID();
        var safe = new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "normalized_skill", "java", false);
        var defect = new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "normalized_skill", "java", true);
        var first = new MatchReview(UUID.randomUUID(), reportId, "human", "model", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false, List.of(), List.of(safe, defect), "evidence_defect_identified", NOW);
        var second = new MatchReview(UUID.randomUUID(), reportId, "human", "model", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false, List.of(), List.of(defect, safe), "evidence_defect_identified", NOW);
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void divergenceIgnoresReviewComponentsMissingFromBaseline() {
        var baseline = completeReport(80, MatchOutcome.STRONG_MATCH, false);
        var review = new MatchReview(UUID.randomUUID(), baseline.id(), "human", "model", "v1", 80,
                MatchOutcome.STRONG_MATCH, false,
                List.of(new ComponentScore(MatchComponent.DELIVERY, 0, 10, EvidenceStatus.UNKNOWN)),
                List.of(), "review_consistent", NOW);
        assertEquals(java.util.Set.of(), MatchDivergencePolicy.V1.reasons(baseline, review));
    }

    private static MatchReport completeReport(int overall, MatchOutcome outcome, boolean blocker) {
        return new MatchReport(UUID.randomUUID(), PROFILE_ID, JOB_ID, NOW, NOW, "v1", overall, 100,
                outcome, blocker,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 40, 40, EvidenceStatus.MATCH)),
                List.of(), NOW);
    }

    private static ProfileAggregate profile(List<String> skills, List<Experience> experiences) {
        var profile = new UserProfile(PROFILE_ID, "Candidate", "candidate@example.test", null, null, NOW, NOW);
        var profileSkills = skills.stream().map(skill -> new ProfileSkill(
                UUID.randomUUID(), PROFILE_ID, skill, skill.toLowerCase(), null, 0, NOW)).toList();
        return new ProfileAggregate(profile, List.of(), List.of(), profileSkills, List.of(), List.of(),
                experiences, List.of(), List.of());
    }

    private static JobAggregate job(List<String> skills, String experience, String seniority, String description) {
        var posting = new JobPosting(JOB_ID, "text", null, "Engineer", null, null, description,
                experience, null, seniority, null, "fingerprint", NOW, NOW);
        var jobSkills = skills.stream().map(skill -> new JobSkill(
                UUID.randomUUID(), JOB_ID, skill, skill, true, 0, NOW)).toList();
        return new JobAggregate(posting, jobSkills, null,
                new JobTextIngestion(UUID.randomUUID(), JOB_ID, "test", "hash", NOW));
    }

    private static MatchEvidence evidence(String source, String fact) {
        return new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, source, fact, false);
    }

    private static MatchReview reviewWithScore(int score) {
        return new MatchReview(UUID.randomUUID(), UUID.randomUUID(), "human", "model", "v1", score,
                MatchOutcome.WEAK_MATCH, false, List.of(), List.of(), "review_consistent", NOW);
    }

    private static MatchReport createdWithScore(int score) {
        var remaining = score;
        var technical = Math.min(remaining, 40); remaining -= technical;
        var experience = Math.min(remaining, 25); remaining -= experience;
        var domain = Math.min(remaining, 15); remaining -= domain;
        var delivery = Math.min(remaining, 10); remaining -= delivery;
        return MatchReport.create(PROFILE_ID, JOB_ID, NOW, NOW, "v1",
                List.of(new ComponentScore(MatchComponent.TECHNICAL, technical, 40, EvidenceStatus.MATCH),
                        new ComponentScore(MatchComponent.EXPERIENCE_SENIORITY, experience, 25, EvidenceStatus.MATCH),
                        new ComponentScore(MatchComponent.DOMAIN, domain, 15, EvidenceStatus.MATCH),
                        new ComponentScore(MatchComponent.DELIVERY, delivery, 10, EvidenceStatus.MATCH),
                        new ComponentScore(MatchComponent.HARD_REQUIREMENTS, remaining, 10, EvidenceStatus.MATCH)), List.of(), NOW);
    }

    private static void assertReportRejected(UUID id, UUID profileId, UUID jobId, Instant profileRevision,
                                             Instant jobRevision, String version, int score, int confidence,
                                             MatchOutcome outcome, Instant createdAt) {
        assertThrows(RuntimeException.class, () -> new MatchReport(id, profileId, jobId, profileRevision, jobRevision,
                version, score, confidence, outcome, false, List.of(), List.of(), createdAt));
    }
}

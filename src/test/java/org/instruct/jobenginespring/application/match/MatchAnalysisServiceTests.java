package org.instruct.jobenginespring.application.match;

import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.match.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MatchAnalysisServiceTests {
    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");

    @Test
    void submittingSameReviewIsIdempotentAndCreatesStableDisagreement() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(profiles, jobs, repository, new DeterministicMatchScorer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var requested = draft(report.id());
        var persisted = new MatchReview(UUID.randomUUID(), requested.reportId(), requested.reviewer(), requested.model(),
                requested.reviewVersion(), requested.overallScore(), requested.outcome(), requested.blockerMismatch(),
                requested.components(), requested.evidence(), requested.summary(), NOW);
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(repository.saveReview(any())).thenReturn(persisted);
        when(repository.saveDisagreement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var first = service.submitReview(requested);
        var second = service.submitReview(requested);

        assertEquals(first.review(), second.review());
        assertEquals(first.disagreement().fingerprint(), second.disagreement().fingerprint());
        assertEquals(first.disagreement().id(), second.disagreement().id());
    }

    @Test
    void semanticallyEquivalentDisagreementIgnoresReviewIdentityAndHarmlessMetadata() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var firstDraft = draft(report.id());
        var secondDraft = new MatchAnalysisService.ReviewDraft(report.id(), "second-reviewer", "second-model", "v2",
                firstDraft.overallScore(), firstDraft.outcome(), firstDraft.blockerMismatch(), firstDraft.components(),
                firstDraft.evidence(), "outcome_adjustment");
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(repository.saveReview(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveDisagreement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var first = service.submitReview(firstDraft);
        var second = service.submitReview(secondDraft);

        assertNotEquals(first.review().id(), second.review().id());
        assertEquals(first.disagreement().fingerprint(), second.disagreement().fingerprint());
        assertEquals("divergence-v1", first.disagreement().policyVersion());
    }

    @Test
    void submitReviewAllowsBaselineFactsAndClosedDefectCodes() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var baselineFact = new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH,
                "normalized_skill", "java", false);
        var base = report();
        var report = new MatchReport(base.id(), base.profileId(), base.jobId(), base.profileRevision(), base.jobRevision(),
                base.algorithmVersion(), base.overallScore(), base.confidence(), base.outcome(), base.blockerMismatch(),
                base.components(), List.of(baselineFact), base.createdAt());
        var defect = new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MISMATCH,
                "advisory_defect", "structured_evidence_incorrect", true);
        var draft = new MatchAnalysisService.ReviewDraft(report.id(), "human", "provider-neutral", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false, List.of(), List.of(baselineFact, defect), "evidence_defect_identified");
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(repository.saveReview(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveDisagreement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.submitReview(draft);

        assertEquals(List.of("structured_evidence_incorrect"), result.disagreement().evidenceDefectCodes());
    }

    @Test
    void acknowledgementCanRecordExistingLinearIdWithoutProviderCall() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var disagreement = disagreement();
        when(repository.findDisagreement(disagreement.id())).thenReturn(Optional.of(disagreement));
        when(repository.updateDisagreement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var acknowledged = service.acknowledgeDisagreement(disagreement.id(), "JOB-54");

        assertEquals(DisagreementStatus.ACKNOWLEDGED, acknowledged.status());
        assertEquals("JOB-54", acknowledged.linearIssueId());
    }

    @Test
    void batchFailureNeverReturnsUnderlyingExceptionMessage() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var pairAnalyzer = mock(MatchPairAnalyzer.class);
        var profile = mock(org.instruct.jobenginespring.domain.profile.ProfileAggregate.class);
        var profileId = UUID.randomUUID();
        var service = new MatchAnalysisService(profiles, jobs, mock(MatchAnalysisRepository.class), pairAnalyzer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var aggregate = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        var jobId = UUID.randomUUID();
        when(aggregate.job().id()).thenReturn(jobId);
        when(profiles.findProfileAggregate(profileId)).thenReturn(Optional.of(profile));
        when(jobs.listJobAggregates()).thenReturn(List.of(aggregate));
        when(pairAnalyzer.analyze(profile, aggregate))
                .thenThrow(new IllegalStateException("jdbc://private-host secret-token"));

        var result = service.analyzeAll(profileId);

        assertEquals("analysis failed", result.failed().getFirst().error());
    }

    @Test
    void matchingReviewCreatesNoDisagreementAndMissingRecordsAreSanitizedDomainErrors() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var draft = new MatchAnalysisService.ReviewDraft(report.id(), "human", "provider-neutral", "v1",
                report.overallScore(), report.outcome(), report.blockerMismatch(), report.components(), report.evidence(), "review_consistent");
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(repository.saveReview(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.submitReview(draft);
        var nullEvidence = service.submitReview(new MatchAnalysisService.ReviewDraft(report.id(), "human", "provider-neutral",
                "v2", report.overallScore(), report.outcome(), report.blockerMismatch(), report.components(), null,
                "review_consistent"));

        assertNull(result.disagreement());
        assertNull(nullEvidence.disagreement());
        verify(repository, never()).saveDisagreement(any());
        assertThrows(IllegalArgumentException.class, () -> service.getReview(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.getReport(UUID.randomUUID()));
    }

    @Test
    void getAndListDelegateToRepositoryAndComputeStaleness() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(profiles, jobs, repository, new DeterministicMatchScorer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var profile = mock(org.instruct.jobenginespring.domain.profile.UserProfile.class);
        var job = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        when(profile.updatedAt()).thenReturn(NOW.plusSeconds(1));
        when(job.job().updatedAt()).thenReturn(NOW);
        when(profiles.findProfileById(report.profileId())).thenReturn(Optional.of(profile));
        when(jobs.findJobAggregate(report.jobId())).thenReturn(Optional.of(job));
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(repository.listReports(report.profileId(), report.jobId())).thenReturn(List.of(report));

        assertTrue(service.getReport(report.id()).stale());
        assertEquals(1, service.listReports(report.profileId(), report.jobId()).size());
        verify(repository).listReports(report.profileId(), report.jobId());
    }

    @Test
    void listOperationsValidateRequiredReviewIdAndPreserveNullableDisagreementFilter() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(NullPointerException.class, () -> service.listReviews(null));
        assertEquals(List.of(), service.listDisagreements(null));

        verify(repository).listDisagreements(null);
        verify(repository, never()).listReviews(null);
    }

    @Test
    void analyzeValidatesIdsFindsAggregatesScoresAndSaves() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var repository = mock(MatchAnalysisRepository.class);
        var scorer = mock(DeterministicMatchScorer.class);
        var profile = mock(org.instruct.jobenginespring.domain.profile.ProfileAggregate.class);
        var job = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class);
        var report = report();
        when(profiles.findProfileAggregate(report.profileId())).thenReturn(Optional.of(profile));
        when(jobs.findJobAggregate(report.jobId())).thenReturn(Optional.of(job));
        when(scorer.score(profile, job, NOW)).thenReturn(report);
        when(repository.saveReport(report)).thenReturn(report);
        var service = new MatchAnalysisService(profiles, jobs, repository, scorer, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(report, service.analyze(report.profileId(), report.jobId()).report());
        assertThrows(NullPointerException.class, () -> service.analyze(null, report.jobId()));
        assertThrows(NullPointerException.class, () -> service.analyze(report.profileId(), null));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(UUID.randomUUID(), report.jobId()));
        when(profiles.findProfileAggregate(report.profileId())).thenReturn(Optional.of(profile));
        when(jobs.findJobAggregate(report.jobId())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.analyze(report.profileId(), report.jobId()));
    }

    @Test
    void analyzeAllReturnsSuccessesAndFailuresAndValidatesProfile() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var pairAnalyzer = mock(MatchPairAnalyzer.class);
        var service = new MatchAnalysisService(profiles, jobs, mock(MatchAnalysisRepository.class), pairAnalyzer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var profile = mock(org.instruct.jobenginespring.domain.profile.ProfileAggregate.class);
        var first = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        var second = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var view = new MatchAnalysisService.ReportView(report(), false);
        when(first.job().id()).thenReturn(firstId);
        when(second.job().id()).thenReturn(secondId);
        when(profiles.findProfileAggregate(profileId)).thenReturn(Optional.of(profile));
        when(jobs.listJobAggregates()).thenReturn(List.of(first, second));
        when(pairAnalyzer.analyze(profile, first)).thenReturn(view.report());
        when(pairAnalyzer.analyze(profile, second)).thenThrow(new IllegalStateException("private detail"));

        var result = service.analyzeAll(profileId);

        assertEquals(List.of(view), result.succeeded());
        assertEquals(List.of(new MatchAnalysisService.PairFailure(profileId, secondId, "analysis failed")), result.failed());
        assertThrows(NullPointerException.class, () -> service.analyzeAll(null));
        var missingProfileId = UUID.randomUUID();
        when(profiles.findProfileAggregate(missingProfileId)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.analyzeAll(missingProfileId));
    }

    @Test
    void analyzeAllLoadsTheProfileOnceAndReusesListedJobAggregates() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var repository = mock(MatchAnalysisRepository.class);
        var scorer = mock(DeterministicMatchScorer.class);
        var profile = mock(org.instruct.jobenginespring.domain.profile.ProfileAggregate.class);
        var first = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        var second = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        var profileId = UUID.randomUUID();
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var firstReport = report();
        var secondReport = report();
        when(first.job().id()).thenReturn(firstId);
        when(second.job().id()).thenReturn(secondId);
        when(profiles.findProfileAggregate(profileId)).thenReturn(Optional.of(profile));
        when(jobs.listJobAggregates()).thenReturn(List.of(first, second));
        when(jobs.findJobAggregate(firstId)).thenReturn(Optional.of(first));
        when(jobs.findJobAggregate(secondId)).thenReturn(Optional.of(second));
        when(scorer.score(profile, first, NOW)).thenReturn(firstReport);
        when(scorer.score(profile, second, NOW)).thenReturn(secondReport);
        when(repository.saveReport(firstReport)).thenReturn(firstReport);
        when(repository.saveReport(secondReport)).thenReturn(secondReport);
        var service = new MatchAnalysisService(profiles, jobs, repository, scorer, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.analyzeAll(profileId);

        assertEquals(2, result.succeeded().size());
        assertTrue(result.failed().isEmpty());
        verify(profiles).findProfileAggregate(profileId);
        verify(jobs, never()).findJobAggregate(any());
        verify(scorer).score(profile, first, NOW);
        verify(scorer).score(profile, second, NOW);
    }

    @Test
    void reportViewsCoverFreshMissingAndChangedSources() {
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(profiles, jobs, repository, new DeterministicMatchScorer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var profile = mock(org.instruct.jobenginespring.domain.profile.UserProfile.class);
        var job = mock(org.instruct.jobenginespring.domain.job.JobAggregate.class, RETURNS_DEEP_STUBS);
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        when(profile.updatedAt()).thenReturn(report.profileRevision());
        when(job.job().updatedAt()).thenReturn(report.jobRevision());
        when(profiles.findProfileById(report.profileId())).thenReturn(Optional.of(profile));
        when(jobs.findJobAggregate(report.jobId())).thenReturn(Optional.of(job));
        assertFalse(service.getReport(report.id()).stale());

        when(profiles.findProfileById(report.profileId())).thenReturn(Optional.empty());
        assertTrue(service.getReport(report.id()).stale());
        when(profiles.findProfileById(report.profileId())).thenReturn(Optional.of(profile));
        when(jobs.findJobAggregate(report.jobId())).thenReturn(Optional.empty());
        assertTrue(service.getReport(report.id()).stale());
    }

    @Test
    void disagreementTransitionsValidateLinkageAndHandleNotFound() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var disagreement = disagreement();
        when(repository.findDisagreement(disagreement.id())).thenReturn(Optional.of(disagreement));
        when(repository.updateDisagreement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var linked = service.linkDisagreement(disagreement.id(), " JOB-54 ");
        assertEquals(DisagreementStatus.LINKED, linked.status());
        assertEquals("JOB-54", linked.linearIssueId());
        var acknowledged = service.acknowledgeDisagreement(disagreement.id(), null);
        assertEquals(disagreement.linearIssueId(), acknowledged.linearIssueId());
        assertThrows(IllegalArgumentException.class, () -> service.linkDisagreement(disagreement.id(), null));
        assertThrows(IllegalArgumentException.class, () -> service.linkDisagreement(disagreement.id(), " "));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeDisagreement(disagreement.id(), " "));
        assertThrows(NullPointerException.class, () -> service.acknowledgeDisagreement(null, null));
        assertThrows(IllegalArgumentException.class, () -> service.linkDisagreement(UUID.randomUUID(), "JOB-55"));
    }

    @Test
    void constructorsAndLookupMethodsValidateRequiredValuesAndDelegateLists() {
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService(null, mock(JobRepository.class),
                mock(MatchAnalysisRepository.class), new DeterministicMatchScorer(), Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService(mock(ProfileRepository.class), null,
                mock(MatchAnalysisRepository.class), new DeterministicMatchScorer(), Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class),
                null, new DeterministicMatchScorer(), Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class),
                mock(MatchAnalysisRepository.class), (DeterministicMatchScorer) null, Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class),
                mock(MatchAnalysisRepository.class), new DeterministicMatchScorer(), null));

        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository);
        var reportId = UUID.randomUUID();
        when(repository.listReviews(reportId)).thenReturn(List.of());
        when(repository.listDisagreements(reportId)).thenReturn(List.of());
        assertEquals(List.of(), service.listReviews(reportId));
        assertEquals(List.of(), service.listDisagreements(reportId));
        assertThrows(NullPointerException.class, () -> service.getReport(null));
        assertThrows(NullPointerException.class, () -> service.getReview(null));
        assertThrows(NullPointerException.class, () -> new MatchAnalysisService.ReportView(null, false));
    }

    @Test
    void submitAndGetReviewCoverMissingReportAndSuccessfulLookup() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        var draft = draft(report.id());
        assertThrows(NullPointerException.class, () -> service.submitReview(null));
        assertThrows(IllegalArgumentException.class, () -> service.submitReview(draft));

        var review = new MatchReview(UUID.randomUUID(), report.id(), "human", "model", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false, List.of(), List.of(), "review_consistent", NOW);
        when(repository.findReview(review.id())).thenReturn(Optional.of(review));
        assertEquals(review, service.getReview(review.id()));
    }

    @Test
    void submitReviewRejectsArbitraryCandidateClaimsBeforePersistence() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        var arbitraryClaim = new MatchEvidence(MatchComponent.EXPERIENCE_SENIORITY, EvidenceStatus.MATCH,
                "advisory", "Alice Smith has seven years of Java experience", false);
        var draft = new MatchAnalysisService.ReviewDraft(report.id(), "human", "provider-neutral", "v1", 80,
                MatchOutcome.STRONG_MATCH, false, List.of(), List.of(arbitraryClaim), "review_consistent");

        assertThrows(IllegalArgumentException.class, () -> service.submitReview(draft));

        verify(repository, never()).saveReview(any());
        verify(repository, never()).saveDisagreement(any());
    }

    @Test
    void submitReviewRejectsArbitrarySummaryBeforePersistence() {
        var repository = mock(MatchAnalysisRepository.class);
        var service = new MatchAnalysisService(mock(ProfileRepository.class), mock(JobRepository.class), repository,
                new DeterministicMatchScorer(), Clock.fixed(NOW, ZoneOffset.UTC));
        var report = report();
        when(repository.findReport(report.id())).thenReturn(Optional.of(report));
        var draft = new MatchAnalysisService.ReviewDraft(report.id(), "human", "provider-neutral", "v1", 80,
                MatchOutcome.STRONG_MATCH, false, List.of(), List.of(),
                "Alice Smith has seven years of Java experience");

        assertThrows(IllegalArgumentException.class, () -> service.submitReview(draft));

        verify(repository, never()).saveReview(any());
    }

    private static MatchReport report() {
        return new MatchReport(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW, NOW, "v1", 80, 100,
                MatchOutcome.STRONG_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 40, 40, EvidenceStatus.MATCH)), List.of(), NOW);
    }

    private static MatchAnalysisService.ReviewDraft draft(UUID reportId) {
        return new MatchAnalysisService.ReviewDraft(reportId, "human", "provider-neutral", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 20, 40, EvidenceStatus.PARTIAL)),
                List.of(), "score_adjustment");
    }

    private static MatchDisagreement disagreement() {
        return new MatchDisagreement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "divergence-v1",
                java.util.Set.of(DisagreementReason.OVERALL_DELTA), List.of(), DisagreementStatus.PENDING_ESCALATION,
                null, NOW, NOW);
    }
}

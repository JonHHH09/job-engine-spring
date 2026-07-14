package org.instruct.jobenginespring.application.match;

import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.match.DeterministicMatchScorer;
import org.instruct.jobenginespring.domain.match.MatchReport;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalMatchPairAnalyzerTests {
    private static final Instant NOW = Instant.parse("2026-07-14T15:00:00Z");

    @Test
    void scoresAndPersistsTheProvidedAggregateSnapshots() {
        var repository = mock(MatchAnalysisRepository.class);
        var scorer = mock(DeterministicMatchScorer.class);
        var profile = mock(ProfileAggregate.class);
        var job = mock(JobAggregate.class);
        var report = mock(MatchReport.class);
        when(scorer.score(profile, job, NOW)).thenReturn(report);
        when(repository.saveReport(report)).thenReturn(report);
        var analyzer = new TransactionalMatchPairAnalyzer(mock(ProfileRepository.class), mock(JobRepository.class),
                repository, scorer, Clock.fixed(NOW, ZoneOffset.UTC));

        assertSame(report, analyzer.analyze(profile, job));

        verify(scorer).score(profile, job, NOW);
        verify(repository).saveReport(report);
    }

    @Test
    void validatesDependenciesAndAggregateSnapshots() {
        var repository = mock(MatchAnalysisRepository.class);
        var profiles = mock(ProfileRepository.class);
        var jobs = mock(JobRepository.class);
        var scorer = mock(DeterministicMatchScorer.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThrows(NullPointerException.class, () -> new TransactionalMatchPairAnalyzer(
                null, jobs, repository, scorer, clock));
        assertThrows(NullPointerException.class, () -> new TransactionalMatchPairAnalyzer(
                profiles, null, repository, scorer, clock));
        assertThrows(NullPointerException.class, () -> new TransactionalMatchPairAnalyzer(
                profiles, jobs, null, scorer, clock));
        assertThrows(NullPointerException.class, () -> new TransactionalMatchPairAnalyzer(
                profiles, jobs, repository, null, clock));
        assertThrows(NullPointerException.class, () -> new TransactionalMatchPairAnalyzer(
                profiles, jobs, repository, scorer, null));

        var analyzer = new TransactionalMatchPairAnalyzer(profiles, jobs, repository, scorer, clock);
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null, mock(JobAggregate.class)));
        assertThrows(NullPointerException.class, () -> analyzer.analyze(mock(ProfileAggregate.class), null));
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null, java.util.UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> analyzer.analyze(java.util.UUID.randomUUID(), null));
    }

    @Test
    void springProxyRunsEveryPairInARequiresNewTransaction() {
        var repository = mock(MatchAnalysisRepository.class);
        var scorer = mock(DeterministicMatchScorer.class);
        var profile = mock(ProfileAggregate.class);
        var job = mock(JobAggregate.class);
        var report = mock(MatchReport.class);
        var transactionManager = new RecordingTransactionManager();
        when(scorer.score(profile, job, NOW)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return report;
        });
        when(repository.saveReport(report)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return report;
        });
        var target = new TransactionalMatchPairAnalyzer(mock(ProfileRepository.class), mock(JobRepository.class),
                repository, scorer, Clock.fixed(NOW, ZoneOffset.UTC));
        var interceptor = new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvisor(new TransactionAttributeSourceAdvisor(interceptor));
        proxyFactory.setInterfaces(MatchPairAnalyzer.class);
        var proxy = (MatchPairAnalyzer) proxyFactory.getProxy();

        assertSame(report, proxy.analyze(profile, job));

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionManager.lastDefinition.getPropagationBehavior());
        assertEquals(1, transactionManager.commits);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private TransactionDefinition lastDefinition;
        private int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lastDefinition = definition;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

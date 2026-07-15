package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.job.port.JobAnalysisRunRepository;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.port.JobPostingAnalysisPort;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobExternalWorkTransactionBoundaryTests {

    @Test
    void linkFetchRunsWithoutAnActiveTransaction() {
        JobRepository repository = mock(JobRepository.class);
        when(repository.findByNormalizedSourceUrl(any())).thenReturn(Optional.empty());
        when(repository.findByCanonicalFingerprint(any())).thenReturn(Optional.empty());
        when(repository.saveJobAggregate(any())).thenAnswer(invocation -> invocation.getArgument(0, JobAggregate.class));
        JobLinkContentFetcher fetcher = url -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new JobLinkContentFetcher.JobLinkFetchResult(url, "Developer", "Build Java services", 200);
        };
        JobService service = transactionalProxy(new JobService(repository, fetcher), JobService.class);

        service.addJobFromLink(new JobService.AddJobFromLinkRequest(
                "https://example.test/jobs/1", null, null, null, null, null, null, null, null, null, null
        ));
    }

    @Test
    void fetchAndProviderAnalysisRunWithoutAnActiveTransaction() {
        JobAnalysisRunRepository repository = mock(JobAnalysisRunRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JobLinkContentFetcher fetcher = url -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new JobLinkContentFetcher.JobLinkFetchResult(url, "Developer", "Build secure Java services for customers", 200);
        };
        JobPostingAnalysisPort provider = request -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new JobPostingAnalysisPort.JobPostingAnalysisResponse(
                    "Developer", null, null, "Build secure Java services for customers", java.util.List.of(),
                    null, null, null, null, null, java.util.List.of()
            );
        };
        JobAnalysisService service = transactionalProxy(new JobAnalysisService(
                repository, fetcher, provider, mock(JobService.class)
        ), JobAnalysisService.class);

        service.analyzeJobLink(new JobAnalysisService.AnalyzeJobLinkRequest("https://example.test/jobs/1"));
    }

    private static <T> T transactionalProxy(T target, Class<T> type) {
        var interceptor = new TransactionInterceptor(
                new TestTransactionManager(),
                new AnnotationTransactionAttributeSource()
        );
        var factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

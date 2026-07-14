package org.instruct.jobenginespring.application;

import org.instruct.jobenginespring.adapter.out.postgres.document.PostgresDocumentRepository;
import org.instruct.jobenginespring.adapter.out.postgres.job.PostgresJobRepository;
import org.instruct.jobenginespring.application.document.GermanResumePersistenceService;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.JobService;
import org.instruct.jobenginespring.application.profile.ProfileIdentityMatcher;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionPersistenceService;
import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@Testcontainers
class PersistenceTransactionProxyIntegrationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
    }

    @Test
    void jobAndDocumentPersistenceMethodsUseSpringManagedJdbcTransactions() {
        var jobRepository = spy(new PostgresJobRepository(new NamedParameterJdbcTemplate(dataSource)));
        var documentRepository = spy(new PostgresDocumentRepository(new NamedParameterJdbcTemplate(dataSource)));
        doAnswer(invocation -> {
            assertJdbcTransactionActive();
            return invocation.getArgument(0);
        }).when(jobRepository).saveJobAggregate(any());
        doReturn(Optional.empty()).when(jobRepository).findByNormalizedSourceUrl(any());
        doReturn(Optional.empty()).when(jobRepository).findByCanonicalFingerprint(any());
        doAnswer(invocation -> {
            assertJdbcTransactionActive();
            return null;
        }).when(documentRepository).saveFile(any());

        try (var context = context()) {
            context.registerBean(JobRepository.class, () -> jobRepository);
            context.registerBean(DocumentRepository.class, () -> documentRepository);
            context.registerBean(JobLinkContentFetcher.class, () -> url -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
                return new JobLinkContentFetcher.JobLinkFetchResult(
                        url, "Developer", "Build Java services", 200
                );
            });
            context.register(JobService.class);
            context.refresh();

            assertTrue(AopUtils.isAopProxy(context.getBean(JobRepository.class)));
            assertTrue(AopUtils.isAopProxy(context.getBean(DocumentRepository.class)));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            context.getBean(JobService.class).addJobFromLink(new JobService.AddJobFromLinkRequest(
                    "https://example.test/jobs/1", null, null, null, null, null,
                    null, null, null, null, null
            ));
            context.getBean(DocumentRepository.class).saveFile(mock(StoredDocumentFile.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Test
    void profileAndResumePersistenceCollaboratorsUseSpringManagedJdbcTransactions() {
        var profilePersistence = spy(new ProfilePdfIngestionPersistenceService(
                mock(ProfileService.class),
                mock(ProfileIdentityMatcher.class),
                mock(ProfilePdfSourceRepository.class)
        ));
        var resumePersistence = spy(new GermanResumePersistenceService(
                mock(ResumeRepository.class),
                mock(DocumentRepository.class),
                mock(GeneratedResumeFileRepository.class),
                mock(org.instruct.jobenginespring.application.document.GeneratedResumeCleanupService.class),
                mock(TransactionLifecycle.class)
        ));
        doAnswer(invocation -> {
            assertJdbcTransactionActive();
            return null;
        }).when(profilePersistence).persist(any(), any(), any());
        doAnswer(invocation -> {
            assertJdbcTransactionActive();
            return null;
        }).when(resumePersistence).replace(any(), anyList());

        try (var context = context()) {
            context.registerBean(ProfilePdfIngestionPersistenceService.class, () -> profilePersistence);
            context.registerBean(GermanResumePersistenceService.class, () -> resumePersistence);
            context.refresh();

            var profileProxy = context.getBean(ProfilePdfIngestionPersistenceService.class);
            var resumeProxy = context.getBean(GermanResumePersistenceService.class);
            assertTrue(AopUtils.isAopProxy(profileProxy));
            assertTrue(AopUtils.isAopProxy(resumeProxy));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            profileProxy.persist(
                    mock(ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest.class),
                    mock(DocumentStorageService.StoredPdfTextExtractionResult.class),
                    mock(ProfileService.ProfileWriteRequest.class)
            );
            resumeProxy.replace(mock(ResumeRepository.ResumeAggregateWrite.class), List.of());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        return context;
    }

    private static void assertJdbcTransactionActive() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}

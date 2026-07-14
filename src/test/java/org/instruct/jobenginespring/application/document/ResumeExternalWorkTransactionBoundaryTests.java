package org.instruct.jobenginespring.application.document;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ResumeExternalWorkTransactionBoundaryTests {

    @Test
    void masterResumeWorkflowStartsWithoutAnActiveTransaction() {
        UUID profileId = UUID.randomUUID();
        ProfileResumePdfGenerationWorkflow workflow = mock(ProfileResumePdfGenerationWorkflow.class);
        when(workflow.requireProfile(profileId)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new BoundaryReached();
        });
        GeneratePdfResumeService service = transactionalProxy(
                new GeneratePdfResumeService(workflow, Path.of("target/test-resumes")),
                GeneratePdfResumeService.class
        );

        assertThrows(BoundaryReached.class, () -> service.generatePdfResume(
                new GeneratePdfResumeService.GeneratePdfResumeRequest(profileId)
        ));
    }

    @Test
    void resumeEntryPointsDoNotOpenTransactionsAroundRenderingTranslationOrFileWrites() throws Exception {
        assertNull(transactionalAnnotation(
                GeneratePdfResumeService.class, "generatePdfResume", GeneratePdfResumeService.GeneratePdfResumeRequest.class
        ));
        assertNull(transactionalAnnotation(
                GenerateCaPdfResumeService.class, "generateCanadianPdfResume", GenerateCaPdfResumeService.GenerateCaPdfResumeRequest.class
        ));
        assertNull(transactionalAnnotation(
                GenerateCanadianFrenchPdfResumeService.class,
                "generateCanadianFrenchPdfResume",
                GenerateCanadianFrenchPdfResumeService.GenerateCanadianFrenchPdfResumeRequest.class
        ));
        assertNull(transactionalAnnotation(
                GenerateGermanTailoredResumeService.class,
                "generate",
                GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest.class
        ));
    }

    private static Transactional transactionalAnnotation(Class<?> type, String method, Class<?> parameter) throws Exception {
        return type.getMethod(method, parameter).getAnnotation(Transactional.class);
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

    private static final class BoundaryReached extends RuntimeException {
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}

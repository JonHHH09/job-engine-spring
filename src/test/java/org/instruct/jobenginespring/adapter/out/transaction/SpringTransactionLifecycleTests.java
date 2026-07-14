package org.instruct.jobenginespring.adapter.out.transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringTransactionLifecycleTests {

    private final SpringTransactionLifecycle lifecycle = new SpringTransactionLifecycle();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsCommitAndCompletionImmediatelyAndIgnoresRollbackWithoutSynchronization() {
        AtomicInteger calls = new AtomicInteger();

        lifecycle.afterCommit(calls::incrementAndGet);
        lifecycle.afterRollback(calls::incrementAndGet);
        lifecycle.afterCompletion(calls::incrementAndGet);

        assertEquals(2, calls.get());
    }

    @Test
    void dispatchesRegisteredActionsForMatchingCompletionStatus() {
        AtomicInteger commitCalls = new AtomicInteger();
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();

        lifecycle.afterCommit(commitCalls::incrementAndGet);
        lifecycle.afterRollback(rollbackCalls::incrementAndGet);
        lifecycle.afterCompletion(completionCalls::incrementAndGet);
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertEquals(1, commitCalls.get());
        assertEquals(1, rollbackCalls.get());
        assertEquals(2, completionCalls.get());
    }

    @Test
    void rejectsNullActions() {
        assertThrows(NullPointerException.class, () -> lifecycle.afterCommit(null));
        assertThrows(NullPointerException.class, () -> lifecycle.afterRollback(null));
        assertThrows(NullPointerException.class, () -> lifecycle.afterCompletion(null));
    }
}

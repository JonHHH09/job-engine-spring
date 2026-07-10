package org.instruct.jobenginespring.adapter.out.transaction;

import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
public class SpringTransactionLifecycle implements TransactionLifecycle {

    @Override
    public void afterCommit(Runnable action) {
        Runnable safeAction = Objects.requireNonNull(action, "action must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeAction.run();
            }
        });
    }

    @Override
    public void afterRollback(Runnable action) {
        Runnable safeAction = Objects.requireNonNull(action, "action must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    safeAction.run();
                }
            }
        });
    }
}

package org.instruct.jobenginespring.application.document.port;

/** Registers filesystem cleanup against the surrounding application transaction. */
public interface TransactionLifecycle {

    void afterCommit(Runnable action);

    void afterRollback(Runnable action);
}

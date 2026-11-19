package io.github.dhluong90.bpm.process.internal.store;

import java.util.function.Supplier;

/** Joins the caller's transaction, or opens one when there is none. */
@FunctionalInterface
public interface TransactionRunner {

    /**
     * @param work the work
     * @param <T> result type
     * @return the result; an exception rolls the transaction back
     */
    <T> T inTransaction(Supplier<T> work);

    /**
     * Runs an action after the current transaction commits, and never when it rolls back; without a transaction it
     * runs at once. Used for event delivery, which is therefore at most once.
     *
     * @param action the action
     */
    default void afterCommit(Runnable action) {
        action.run();
    }
}

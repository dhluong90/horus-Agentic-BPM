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
     * Whether the current thread is inside a transaction of this runner's transaction manager. The async executor
     * refuses to call an adapter when this is true (ADR-0008, TR-ASY-01).
     *
     * @return true inside a transaction; false when the runner cannot tell
     */
    default boolean activeOnThisThread() {
        return false;
    }
}

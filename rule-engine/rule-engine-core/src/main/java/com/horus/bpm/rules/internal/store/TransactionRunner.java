package com.horus.bpm.rules.internal.store;

import java.util.function.Supplier;

/** Runs work in the caller's transaction, or in a new one when there is none. */
public interface TransactionRunner {

    /**
     * Runs work transactionally.
     *
     * @param work the work
     * @param <T> the result type
     * @return the result
     */
    <T> T inTransaction(Supplier<T> work);

    /**
     * Runs a read in a read-only transaction. Used off the request path, by the cursor key refresh. The default
     * runs in an ordinary transaction, for stores without transactions.
     *
     * @param work the work
     * @param <T> the result type
     * @return the result
     */
    default <T> T inReadOnlyTransaction(Supplier<T> work) {
        return inTransaction(work);
    }
}

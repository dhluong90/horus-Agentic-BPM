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
     * Runs engine-internal work in a transaction of its own, committed before this method returns, whatever the
     * caller's transaction does afterwards (rolled back, read-only). Used for state every replica must agree on,
     * such as the cursor key. The default runs in the caller's transaction, for stores without transactions.
     *
     * @param work the work
     * @param <T> the result type
     * @return the result
     */
    default <T> T inOwnTransaction(Supplier<T> work) {
        return inTransaction(work);
    }
}

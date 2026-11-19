package io.github.dhluong90.bpm.rules.internal.store;

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
}

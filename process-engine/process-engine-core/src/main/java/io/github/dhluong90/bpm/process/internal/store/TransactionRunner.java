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
     * Runs {@code action} once the current transaction has committed, and never when it rolls back. A runner that
     * cannot register a callback, or a call made outside a transaction, runs the action at once. Used for metrics,
     * which count committed work only (PRE-084); the action must not throw and must not touch the database.
     *
     * @param action the action
     */
    default void afterCommit(Runnable action) {
        action.run();
    }

    /**
     * Whether the current thread is inside a transaction of this runner's transaction manager that the engine did not
     * open itself, for example a host's {@code @Transactional} method. Transactions the engine opens through a
     * {@link #tracked} runner are seen whatever the runner, so the async executor's guard (ADR-0008, TR-ASY-01) never
     * depends on this method alone. A runner that cannot tell answers false; a host transaction it cannot see is then
     * not detected.
     *
     * @return true inside such a transaction; false when there is none or the runner cannot tell
     */
    default boolean activeOnThisThread() {
        return false;
    }

    /**
     * Wraps a runner so that every transaction opened through it, on any wrapped runner, is counted on the current
     * thread. The engine wraps the runner it is given, so the adapter-outside-transaction guard works with any runner.
     *
     * @param runner the runner
     * @return the tracked runner; the runner itself when it is already tracked
     */
    static TransactionRunner tracked(TransactionRunner runner) {
        if (runner instanceof Tracked) {
            return runner;
        }
        return new Tracked(runner);
    }

    /** A runner that counts the engine's transactions on the current thread. */
    final class Tracked implements TransactionRunner {

        private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
        private final TransactionRunner delegate;

        private Tracked(TransactionRunner delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "runner must not be null");
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return delegate.inTransaction(() -> {
                int[] depth = DEPTH.get();
                depth[0]++;
                try {
                    return work.get();
                } finally {
                    if (--depth[0] == 0) {
                        DEPTH.remove();
                    }
                }
            });
        }

        @Override
        public void afterCommit(Runnable action) {
            delegate.afterCommit(action);
        }

        @Override
        public boolean activeOnThisThread() {
            boolean engine = DEPTH.get()[0] > 0;
            if (!engine) {
                DEPTH.remove();
            }
            return engine || delegate.activeOnThisThread();
        }
    }
}

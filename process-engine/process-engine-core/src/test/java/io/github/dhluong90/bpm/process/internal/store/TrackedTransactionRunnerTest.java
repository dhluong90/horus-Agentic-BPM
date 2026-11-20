package io.github.dhluong90.bpm.process.internal.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The adapter-outside-transaction guard (ADR-0008, TR-ASY-01) sees the engine's own transactions with any runner,
 * not only Spring's: a plain runner here opens no real transaction and cannot tell, yet the tracked runner can.
 */
class TrackedTransactionRunnerTest {

    /** A host runner that knows nothing about transactions on the thread. */
    private static final TransactionRunner PLAIN = new TransactionRunner() {
        @Override
        public <T> T inTransaction(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };

    @Test
    void TR_ASY_01_a_tracked_runner_sees_its_own_transactions_whatever_the_runner() throws Exception {
        TransactionRunner tracked = TransactionRunner.tracked(PLAIN);
        TransactionRunner other = TransactionRunner.tracked(PLAIN);

        assertThat(tracked.activeOnThisThread()).isFalse();
        assertThat(tracked.inTransaction(tracked::activeOnThisThread)).isTrue();
        assertThat(tracked.inTransaction(other::activeOnThisThread)).isTrue();
        assertThat(tracked.inTransaction(() -> tracked.inTransaction(() -> 1) + (tracked.activeOnThisThread() ? 1 : 0)))
                .isEqualTo(2);
        assertThat(tracked.inTransaction(() -> CompletableFuture.supplyAsync(tracked::activeOnThisThread)
                .orTimeout(10, TimeUnit.SECONDS).join())).isFalse();
        assertThat(tracked.activeOnThisThread()).isFalse();
        assertThat(PLAIN.activeOnThisThread()).isFalse();
    }

    @Test
    void TR_ASY_01_the_count_is_restored_when_the_work_throws() {
        TransactionRunner tracked = TransactionRunner.tracked(PLAIN);

        assertThatThrownBy(() -> tracked.inTransaction(() -> {
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tracked.activeOnThisThread()).isFalse();
        assertThat(TransactionRunner.tracked(tracked)).isSameAs(tracked);
    }
}

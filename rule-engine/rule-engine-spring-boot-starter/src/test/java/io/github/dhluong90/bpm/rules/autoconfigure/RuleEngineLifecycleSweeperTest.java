package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** RLE-027: the starter runs the lifecycle sweep on its own daemon thread, and a failed run does not stop it. */
class RuleEngineLifecycleSweeperTest {

    @Test
    void RLE_027_the_sweep_runs_every_interval_and_survives_a_failed_run() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        RuleEngineLifecycleSweeper sweeper = new RuleEngineLifecycleSweeper(() -> {
            int run = runs.incrementAndGet();
            three.countDown();
            if (run == 1) {
                throw new IllegalStateException("database restarting");
            }
            return 1;
        }, Duration.ofMillis(20));
        try {
            sweeper.start();
            sweeper.start(); // a second start does not add a second schedule

            assertThat(three.await(5, TimeUnit.SECONDS)).as("runs after a failed run").isTrue();
        } finally {
            sweeper.destroy();
        }
        int stopped = runs.get();
        Thread.sleep(100);
        assertThat(runs.get()).as("no run after destroy").isLessThanOrEqualTo(stopped + 1);
    }

    @Test
    void RLE_027_run_once_returns_what_this_replica_retired() {
        RuleEngineLifecycleSweeper sweeper = new RuleEngineLifecycleSweeper(() -> 2, Duration.ofMinutes(1));

        assertThat(sweeper.runOnce()).isEqualTo(2);
        sweeper.destroy(); // never started: nothing to stop
    }
}

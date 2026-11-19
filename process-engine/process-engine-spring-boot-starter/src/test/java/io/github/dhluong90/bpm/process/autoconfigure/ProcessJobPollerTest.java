package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The job poller loop never ends silently: an Error skips one poll; a JVM-fatal error stops it with health DOWN. */
class ProcessJobPollerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-03-01T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void PRE_006_an_error_in_one_poll_is_logged_and_the_next_poll_still_runs() throws Exception {
        // Given a poll that throws a NoClassDefFoundError the first time, then succeeds
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch secondPoll = new CountDownLatch(2);
        ProcessJobPoller poller = new ProcessJobPoller(() -> {
            secondPoll.countDown();
            if (calls.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("io/example/MissingAdapter");
            }
            return 0;
        }, "pod-1", Duration.ofMillis(10), CLOCK);

        // When the poller runs
        poller.start();
        boolean ranAgain = secondPoll.await(10, TimeUnit.SECONDS);
        poller.stop();

        // Then the loop survived the Error, and health is UP with the last successful poll
        assertThat(ranAgain).as("a poll after the Error").isTrue();
        assertThat(poller.health().up()).isTrue();
        assertThat(poller.health().details()).containsEntry("replicaId", "pod-1")
                .containsEntry("lastSuccessfulPoll", "2027-03-01T02:00:00Z");
    }

    @Test
    void PRE_006_a_jvm_fatal_error_stops_the_loop_and_marks_health_down() {
        // Given a poll that overflows the stack
        AtomicInteger calls = new AtomicInteger();
        ProcessJobPoller poller = new ProcessJobPoller(() -> {
            calls.incrementAndGet();
            throw new StackOverflowError();
        }, "pod-2", Duration.ofMillis(10), CLOCK);

        // When one iteration runs
        poller.iteration();

        // Then health is DOWN naming the error, no further poll is scheduled, and the poller does not restart
        assertThat(poller.health().up()).isFalse();
        assertThat(poller.health().details()).containsEntry("stoppedBy", "java.lang.StackOverflowError")
                .containsEntry("lastSuccessfulPoll", "none yet");
        assertThat(poller.isRunning()).isFalse();
        poller.start();
        assertThat(poller.isRunning()).as("a stopped poller stays stopped").isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void PRE_006_a_runtime_failure_keeps_health_up() {
        ProcessJobPoller poller = new ProcessJobPoller(() -> {
            throw new IllegalStateException("database down");
        }, "pod-3", Duration.ofMillis(10), CLOCK);

        poller.iteration();

        assertThat(poller.health().up()).isTrue();
    }
}

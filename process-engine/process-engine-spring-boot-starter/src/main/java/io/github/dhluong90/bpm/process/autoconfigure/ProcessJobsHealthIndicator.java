package io.github.dhluong90.bpm.process.autoconfigure;

import java.util.Objects;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Job poller health on Spring Boot 4: DOWN after a JVM-fatal error stopped the loop; details name the last poll. */
final class ProcessJobsHealthIndicator extends AbstractHealthIndicator {

    private final ProcessJobPoller poller;

    ProcessJobsHealthIndicator(ProcessJobPoller poller) {
        super("Process Engine job poller health check failed");
        this.poller = Objects.requireNonNull(poller, "poller must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        ProcessJobPoller.Health health = poller.health();
        builder.status(health.up() ? Status.UP : Status.DOWN).withDetails(health.details());
    }
}

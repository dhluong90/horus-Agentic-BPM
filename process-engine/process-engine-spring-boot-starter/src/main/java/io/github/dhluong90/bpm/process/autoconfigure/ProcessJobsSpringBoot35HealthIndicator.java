package io.github.dhluong90.bpm.process.autoconfigure;

import java.util.Objects;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Job poller health on Spring Boot 3.5 with Actuator; same content as {@link ProcessJobsHealthIndicator}. */
final class ProcessJobsSpringBoot35HealthIndicator extends AbstractHealthIndicator {

    private final ProcessJobPoller poller;

    ProcessJobsSpringBoot35HealthIndicator(ProcessJobPoller poller) {
        super("Process Engine job poller health check failed");
        this.poller = Objects.requireNonNull(poller, "poller must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        ProcessJobPoller.Health health = poller.health();
        builder.status(health.up() ? Status.UP : Status.DOWN).withDetails(health.details());
    }
}

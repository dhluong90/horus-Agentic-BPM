package com.horus.bpm.rules.autoconfigure;

import java.util.Objects;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Spring Boot 3.5 adapter of {@link RuleEngineHealthCheck}, contributed as {@code ruleEngine}. Compiled
 * against the Spring Boot 3.5 Actuator health API, a provided dependency of this starter; loaded only on
 * a host that has that API and not the Spring Boot 4 health module (ADR-0011).
 */
final class RuleEngineSpringBoot35HealthIndicator extends AbstractHealthIndicator {

    private final RuleEngineHealthCheck check;

    RuleEngineSpringBoot35HealthIndicator(RuleEngineHealthCheck check) {
        super(RuleEngineHealthCheck.FAILURE_MESSAGE);
        this.check = Objects.requireNonNull(check, "check must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        RuleEngineHealthCheck.Result result = check.check();
        builder.status(result.up() ? Status.UP : Status.DOWN).withDetails(result.details());
    }
}

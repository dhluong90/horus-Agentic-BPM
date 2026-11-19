package com.horus.bpm.rules.autoconfigure;

import java.util.Objects;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Spring Boot 4 adapter of {@link RuleEngineHealthCheck}, contributed as {@code ruleEngine}. */
final class RuleEngineHealthIndicator extends AbstractHealthIndicator {

    private final RuleEngineHealthCheck check;

    RuleEngineHealthIndicator(RuleEngineHealthCheck check) {
        super(RuleEngineHealthCheck.FAILURE_MESSAGE);
        this.check = Objects.requireNonNull(check, "check must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        RuleEngineHealthCheck.Result result = check.check();
        builder.status(result.up() ? Status.UP : Status.DOWN).withDetails(result.details());
    }
}

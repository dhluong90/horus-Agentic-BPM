package com.horus.bpm.rules.autoconfigure;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches unless the {@code ruleEngine} health contributor is switched off, with the same properties on
 * both Spring Boot lines: {@code management.health.ruleEngine.enabled}, or else
 * {@code management.health.defaults.enabled}; on by default (ADR-0024). Written here because the
 * {@code @ConditionalOnEnabledHealthIndicator} annotation lives in a different module on each line.
 */
final class OnRuleEngineHealthEnabledCondition extends SpringBootCondition {

    static final String ENABLED = "management.health.ruleEngine.enabled";
    static final String DEFAULTS_ENABLED = "management.health.defaults.enabled";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        String value = environment.getProperty(ENABLED, environment.getProperty(ENABLED.toLowerCase(Locale.ROOT)));
        String source = ENABLED;
        if (value == null) {
            value = environment.getProperty(DEFAULTS_ENABLED);
            source = DEFAULTS_ENABLED;
        }
        if (value == null || Boolean.parseBoolean(value.strip())) {
            return ConditionOutcome.match("ruleEngine health contributor enabled");
        }
        return ConditionOutcome.noMatch("ruleEngine health contributor disabled by " + source);
    }
}

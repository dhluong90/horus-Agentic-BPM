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
 *
 * <p>Values are read as Spring Boot reads them for its own health indicators, through the environment's
 * conversion service: {@code true}, {@code on}, {@code yes} and {@code 1} enable, {@code false},
 * {@code off}, {@code no} and {@code 0} disable, an empty value counts as unset, and any other value
 * fails start-up. A typo never switches the component off silently.
 */
final class OnRuleEngineHealthEnabledCondition extends SpringBootCondition {

    static final String ENABLED = "management.health.ruleEngine.enabled";
    static final String DEFAULTS_ENABLED = "management.health.defaults.enabled";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        Boolean enabled = environment.getProperty(ENABLED, Boolean.class);
        if (enabled == null) {
            enabled = environment.getProperty(ENABLED.toLowerCase(Locale.ROOT), Boolean.class);
        }
        if (enabled != null) {
            return outcome(enabled, ENABLED);
        }
        Boolean defaultsEnabled = environment.getProperty(DEFAULTS_ENABLED, Boolean.class);
        if (defaultsEnabled != null) {
            return outcome(defaultsEnabled, DEFAULTS_ENABLED);
        }
        return ConditionOutcome.match("ruleEngine health contributor enabled by default");
    }

    private static ConditionOutcome outcome(boolean enabled, String source) {
        return enabled
                ? ConditionOutcome.match("ruleEngine health contributor enabled by " + source)
                : ConditionOutcome.noMatch("ruleEngine health contributor disabled by " + source);
    }
}

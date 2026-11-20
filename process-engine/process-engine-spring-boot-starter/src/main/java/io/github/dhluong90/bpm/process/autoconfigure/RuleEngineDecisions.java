package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.util.Optional;
import org.springframework.beans.factory.BeanFactory;

/**
 * Builds the {@link RuleEngineDecisionPort} when the host has a {@link RuleEngine} (ADR-0027). The only class of the
 * starter that names Rule Engine types besides the adapter; it is loaded only after the auto-configuration has found
 * the Rule Engine on the class path, so a host with the Process Engine alone never loads a Rule Engine class.
 */
final class RuleEngineDecisions {

    /** The class whose presence makes decisions possible; the Rule Engine dependency is optional. */
    static final String RULE_ENGINE_CLASS = "io.github.dhluong90.bpm.rules.api.RuleEngine";

    private RuleEngineDecisions() {
    }

    /**
     * @param beans the host's beans
     * @return the decision port, or empty when the host has no {@code RuleEngine}
     */
    static Optional<DecisionPort> from(BeanFactory beans) {
        if (beans.getBeanProvider(RuleEngine.class).getIfUnique() == null) {
            return Optional.empty();
        }
        return Optional.of(new RuleEngineDecisionPort(engineWork(beans)));
    }

    /**
     * The Rule Engine's tenant-scoped entry point for this engine's own work, bound to {@code system:process-engine}
     * (ADR-0027). Built here and handed only to the decision port; never registered as a bean, so no host code can
     * inject it.
     */
    private static Optional<TenantScopedRuleEngine> engineWork(BeanFactory beans) {
        return Optional.ofNullable(beans.getBeanProvider(RuleEngines.class).getIfUnique())
                .map(engines -> engines.forEmbeddedEngine(DecisionCaller.ENGINE_IDENTITY));
    }
}

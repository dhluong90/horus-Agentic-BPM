package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import java.util.Optional;
import org.springframework.beans.factory.BeanFactory;

/**
 * Builds the {@link RuleEngineDecisionPort} when the host has a {@link RuleEngine} (ADR-0027). The only class of the
 * starter that names Rule Engine types besides the adapter; it is loaded only after the auto-configuration has found
 * the Rule Engine on the class path, so a host with the Process Engine alone never loads a Rule Engine class. The
 * entry point comes only from the Rule Engine's {@link EmbeddedEngineGrant}, claimed by the adapter (#336).
 */
final class RuleEngineDecisions {

    /** The class whose presence makes decisions possible; the Rule Engine dependency is optional. */
    static final String RULE_ENGINE_CLASS = "io.github.dhluong90.bpm.rules.api.RuleEngine";

    /** The Rule Engine's masking SPI, whose key makes the fingerprints of masked agent inputs (#59). */
    static final String MASKING_POLICY_CLASS = "io.github.dhluong90.bpm.rules.spi.MaskingPolicy";

    private RuleEngineDecisions() {
    }

    /**
     * The masker of agent inputs over the Rule Engine's masking policy (#59): the tenant's key, so the token equals
     * the decision log's for the same value; {@code ***} for a tenant without a key, or without a policy bean.
     *
     * @param beans the host's beans
     * @return the masker
     */
    static io.github.dhluong90.bpm.process.spi.InputMasker inputMasker(BeanFactory beans) {
        io.github.dhluong90.bpm.rules.spi.MaskingPolicy policy =
                beans.getBeanProvider(io.github.dhluong90.bpm.rules.spi.MaskingPolicy.class).getIfUnique();
        if (policy == null) {
            return io.github.dhluong90.bpm.process.spi.InputMasker.REDACT;
        }
        return (tenantId, plainText) -> policy.hmacKey(tenantId).map(key -> key.fingerprint(plainText))
                .orElse(io.github.dhluong90.bpm.process.spi.InputMasker.REDACTED);
    }

    /**
     * Builds the decision port, which claims the Rule Engine's grant of the tenant-scoped entry point, and fails
     * start-up when a grant is offered and was not claimed (#336).
     *
     * @param beans the host's beans
     * @return the decision port, or empty when the host has no {@code RuleEngine}
     * @throws IllegalStateException when the Rule Engine offers a grant that the decision port did not claim
     */
    static Optional<DecisionPort> from(BeanFactory beans) {
        EmbeddedEngineGrant grant = beans.getBeanProvider(EmbeddedEngineGrant.class).getIfUnique();
        Optional<DecisionPort> port = beans.getBeanProvider(RuleEngine.class).getIfUnique() == null
                ? Optional.empty()
                : Optional.of(new RuleEngineDecisionPort(Optional.ofNullable(grant)));
        requireEngineWorkClaimed(grant);
        return port;
    }

    /**
     * With the Rule Engine's grant present, the decision port must have claimed the tenant-scoped entry point. If it
     * did not, because no {@code RuleEngine} bean exists or another party claimed first, start-up fails (#336).
     *
     * @param grant the Rule Engine's grant, or null without one
     */
    static void requireEngineWorkClaimed(EmbeddedEngineGrant grant) {
        if (grant != null && !grant.isClaimed(DecisionCaller.ENGINE_IDENTITY)) {
            throw new IllegalStateException("The Rule Engine offers the tenant-scoped entry point, but the Process "
                    + "Engine did not claim it; decision steps could not run (#336). Is a RuleEngine bean defined?");
        }
    }
}

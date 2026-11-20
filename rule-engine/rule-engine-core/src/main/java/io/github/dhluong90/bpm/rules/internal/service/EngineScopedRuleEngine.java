package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.util.Objects;

/**
 * The tenant-scoped entry point for one registered engine identity, over the default engine. Built only by
 * {@link OneTimeEmbeddedEngineGrant#claim(String, Object)}, which has already checked the identity and the claimant.
 */
final class EngineScopedRuleEngine implements TenantScopedRuleEngine {

    private final DefaultRuleEngine engine;
    private final String engineId;

    EngineScopedRuleEngine(DefaultRuleEngine engine, String engineId) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.engineId = Objects.requireNonNull(engineId, "engineId must not be null");
    }

    @Override
    public String engineId() {
        return engineId;
    }

    @Override
    public DecisionResult evaluate(String tenantId, DecisionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (request.version() != null || request.clock() != null) {
            throw new IllegalArgumentException("Engine work evaluates the selected version at the host clock; a pinned "
                    + "version or a chosen clock needs a caller with rules:pin or rules:simulate");
        }
        return engine.evaluateAs(tenantId, engineId, request);
    }
}

package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Instant;
import java.util.Objects;

/**
 * The tenant-scoped entry point for one registered engine identity, over the default engine. Built only by
 * {@link OneTimeEmbeddedEngineGrant#claim(String, Object)}, which has already checked the identity and the claimant.
 */
final class EngineScopedRuleEngine implements TenantScopedRuleEngine {

    /** Longest {@code onBehalfOf}, the width of {@code rle_decision.on_behalf_of}. */
    static final int ON_BEHALF_OF_MAX_LENGTH = 200;

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
        check(tenantId, request);
        return engine.evaluateAs(tenantId, engineId, request);
    }

    @Override
    public DecisionResult evaluate(String tenantId, String onBehalfOf, Instant evaluationTime, DecisionRequest request) {
        check(tenantId, request);
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        if (onBehalfOf != null && (onBehalfOf.isBlank() || onBehalfOf.length() > ON_BEHALF_OF_MAX_LENGTH)) {
            throw new IllegalArgumentException("onBehalfOf must be 1 to " + ON_BEHALF_OF_MAX_LENGTH + " characters");
        }
        return engine.evaluateAs(tenantId, engineId, onBehalfOf, evaluationTime, request);
    }

    @Override
    public io.github.dhluong90.bpm.rules.embedded.RuleSetSignature signature(String tenantId, String ruleSet,
            Instant at) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(at, "at must not be null");
        return engine.signatureAs(tenantId, ruleSet, at);
    }

    @Override
    public io.github.dhluong90.bpm.rules.embedded.RuleSetSignature signature(String tenantId, String ruleSet,
            int version) {
        requireTenant(tenantId);
        return engine.signatureOfVersionAs(tenantId, ruleSet, version);
    }

    @Override
    public java.util.List<String> publishedRuleSets(String tenantId, Instant at, int limit) {
        requireTenant(tenantId);
        Objects.requireNonNull(at, "at must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return engine.publishedRuleSetsAs(tenantId, at, limit);
    }

    @Override
    public java.util.List<DecisionResult.Outcome> simulate(String tenantId, String ruleSet, int version,
            java.util.List<io.github.dhluong90.bpm.rules.embedded.TimedInput> cases) {
        requireTenant(tenantId);
        Objects.requireNonNull(cases, "cases must not be null");
        return engine.simulateAs(tenantId, ruleSet, version, cases);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    private static void check(String tenantId, DecisionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (request.version() != null || request.clock() != null) {
            throw new IllegalArgumentException("Engine work evaluates the selected version at the host clock; a pinned "
                + "version or a chosen clock needs a caller with rules:pin or rules:simulate");
        }
    }
}

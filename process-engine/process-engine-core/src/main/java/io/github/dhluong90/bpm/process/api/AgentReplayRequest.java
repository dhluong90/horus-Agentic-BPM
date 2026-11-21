package io.github.dhluong90.bpm.process.api;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An offline replay of one agent step's recorded answers against a candidate guardrail (PRE-092, PD-128): the step,
 * the period of recorded answers, and the candidate rule set version, usually a draft. Optionally a candidate output
 * schema; without one, each answer is checked against the schema it was recorded with. The tenant is never part of
 * the request: it comes from the resolver.
 *
 * @param definitionKey the process definition key, for example {@code kyc-onboarding}
 * @param nodeId the agent step id, for example {@code assess-documents}
 * @param from the first day of the period, UTC, inclusive
 * @param to the last day of the period, UTC, inclusive; at most {@code query.max-range} after {@code from}
 * @param candidateRuleSet the candidate guardrail rule set name
 * @param candidateVersion the candidate version number, in any status
 * @param candidateOutputSchema a candidate output schema, or null to use each answer's recorded schema
 * @param idempotencyKey optional; a repeat with the same key and request returns the original run
 */
public record AgentReplayRequest(String definitionKey, String nodeId, LocalDate from, LocalDate to,
        String candidateRuleSet, int candidateVersion, Map<String, Object> candidateOutputSchema,
        String idempotencyKey) {

    public AgentReplayRequest {
        candidateOutputSchema = candidateOutputSchema == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(candidateOutputSchema));
    }

    /**
     * A replay against a candidate guardrail with each answer's recorded schema and no idempotency key.
     *
     * @param definitionKey the process definition key
     * @param nodeId the agent step id
     * @param from first day, inclusive
     * @param to last day, inclusive
     * @param candidateRuleSet the candidate rule set
     * @param candidateVersion the candidate version
     * @return the request
     */
    public static AgentReplayRequest of(String definitionKey, String nodeId, LocalDate from, LocalDate to,
            String candidateRuleSet, int candidateVersion) {
        return new AgentReplayRequest(definitionKey, nodeId, from, to, candidateRuleSet, candidateVersion, null, null);
    }
}

package com.horus.bpm.rules.api;

/**
 * Reads the decision log of the caller's tenant. Every call needs {@code rules:log:read}; the tenant comes from the
 * host's {@code TenantResolver} only (TR-SEC-03). Reads write nothing.
 */
public interface DecisionLogService {

    /**
     * Returns one decision as logged; masked inputs stay fingerprints.
     *
     * @param decisionId the decision id
     * @return the decision
     * @throws DecisionNotFoundException when the caller's tenant has no such decision
     */
    LoggedDecision get(String decisionId);

    /**
     * Finds the decisions whose masked input had a plain value, by its fingerprint under the caller's tenant key.
     *
     * @param query the rule set, input, plain value and period
     * @return the decisions, newest first
     * @throws QueryInvalidException for a period over 31 days, a limit out of range, an input that is not a
     *     name, or a tenant without a masking key
     */
    DecisionSearchResult findByMaskedInput(MaskedInputQuery query);
}

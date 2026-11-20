package io.github.dhluong90.bpm.rules.api;

/**
 * Reads the decision log of the caller's tenant. Reads need {@code rules:log:read}; the masked-input search needs
 * {@code rules:masked:search} instead, which no role holds by default. The tenant comes from the
 * host's {@code TenantResolver} only (TR-SEC-03). Nothing is written to the decision log; each masked-input search
 * writes one audit entry.
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
     * @throws RateLimitedException past {@code masking.search.max-per-minute} searches of this caller in one minute
     * @throws QueryInvalidException for a period over 31 days, a limit out of range, an input that is not a
     *     name, or a tenant without a masking key
     */
    DecisionSearchResult findByMaskedInput(MaskedInputQuery query);

    /**
     * Explains one decision in plain language from its log entry and the stored version it names (RLE-030, RLE-031).
     * Nothing is evaluated and nothing is written; masked inputs stay fingerprints. Needs {@code rules:log:read}.
     *
     * @param decisionId the decision id
     * @param languages the caller's preferred languages, best first, for example {@code ["vi", "en"]}; the first one
     *     the host has a catalogue for is used, else {@code explain.default-language}
     * @return the explanation with its text
     * @throws DecisionNotFoundException when the caller's tenant has no such decision
     */
    default DecisionExplanation explain(String decisionId, java.util.List<String> languages) {
        throw new UnsupportedOperationException("explain is not supported by " + getClass().getName());
    }
}

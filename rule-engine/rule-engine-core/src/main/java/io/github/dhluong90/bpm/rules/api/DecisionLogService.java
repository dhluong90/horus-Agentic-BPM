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

    /**
     * Lists the caller's tenant's dead letters: decisions the asynchronous decision log could not write after its
     * attempts (RLE-231). Needs {@code rules:log:read}.
     *
     * @param limit the most rows to return, 1 to 1,000
     * @return the dead letters, oldest first
     */
    default java.util.List<DeadLetter> deadLetters(int limit) {
        throw new UnsupportedOperationException("dead letters are not supported by " + getClass().getName());
    }

    /**
     * Asks to retry or resolve a dead letter. Nothing happens until a second person approves. Needs
     * {@code rules:log:operate}; the audit entry names the caller and the reason.
     *
     * @param decisionId the decision id
     * @param action what to ask for
     * @param comment why, 3 to 500 characters
     * @param idempotencyKey optional; a repeat with the same key and request returns the first result and writes nothing
     * @return the dead letter, now waiting for a second person
     * @throws DecisionNotFoundException when the tenant has no such dead letter
     * @throws InvalidStateException when the row already has a request waiting
     */
    default DeadLetter requestDeadLetterAction(String decisionId, DeadLetterAction action, String comment,
            String idempotencyKey) {
        throw new UnsupportedOperationException("dead letters are not supported by " + getClass().getName());
    }

    /**
     * Approves or rejects the request on a dead letter. Needs {@code rules:log:approve}, held by someone other than the
     * requester. An approved retry puts the row back in the relay's queue with its attempts from zero; an approved
     * resolve closes it. Neither deletes it.
     *
     * @param decisionId the decision id
     * @param approve true to approve, false to reject (the row stays dead)
     * @param comment why, 3 to 500 characters when rejecting, optional when approving
     * @param idempotencyKey optional; a repeat with the same key and request returns the first result and writes nothing
     * @return the event recorded: {@code RETRY_APPROVED}, {@code RESOLVE_APPROVED} or {@code REQUEST_REJECTED}
     * @throws SelfApprovalForbiddenException when the caller is the requester
     */
    default String decideDeadLetterRequest(String decisionId, boolean approve, String comment,
            String idempotencyKey) {
        throw new UnsupportedOperationException("dead letters are not supported by " + getClass().getName());
    }
}

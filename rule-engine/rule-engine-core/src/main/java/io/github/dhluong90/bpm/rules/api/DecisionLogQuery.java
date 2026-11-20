package io.github.dhluong90.bpm.rules.api;

/**
 * Reads the decision log of the caller's tenant. Safe for concurrent use.
 *
 * <p>Transactions: every method runs in a read-only transaction of its own, or joins the caller's, and writes
 * nothing. It never calls the network.
 *
 * <p>Every method needs a caller with {@code rules:log:read}, resolved by the host's {@code CallerResolver};
 * the tenant comes from the host's {@code TenantResolver} only.
 */
public interface DecisionLogQuery {

    /**
     * Lists the decisions of one rule set in a period of at most {@code query.max-range} (default 31 days),
     * newest first, keyset-paged by log time and decision id.
     *
     * <p>Without a {@code limit} a page holds up to {@code query.max-page-size} (default 200) decisions. Pass
     * {@link Page#nextCursor()} back with the same search to read the next page; a cursor issued for another
     * tenant, rule set or period is refused.
     *
     * @param search the rule set and period
     * @param page the page size and cursor; null for the first page
     * @return one page of decisions; a rule set the tenant does not have gives an empty page
     * @throws QueryInvalidException when the rule set or period is missing, the period is empty or longer than
     *     {@code query.max-range}, the limit is out of range, or the cursor was not issued for this search
     *     ({@code QUERY_INVALID})
     * @throws PermissionDeniedException when the caller lacks {@code rules:log:read}
     */
    Page<DecisionLogEntry> search(DecisionSearch search, PageRequest page);

    /**
     * Lists the decisions of the caller's tenant that carry a correlation id, the host's case reference such as a
     * loan application number, across every rule set or one, in a period of at most {@code query.max-range}, newest
     * first (RLE-070). Masked inputs stay fingerprints. Writes nothing.
     *
     * @param search the correlation id, optional rule set and period
     * @param page the page size and cursor; null for the first page
     * @return one page of decisions
     * @throws QueryInvalidException when the correlation id is missing or outside {@code [A-Za-z0-9._:-]{1,128}},
     *     the period is missing, empty or too long, the limit is out of range or the cursor was not issued for this
     *     search ({@code QUERY_INVALID})
     * @throws PermissionDeniedException when the caller lacks {@code rules:log:read}
     * @throws UnsupportedOperationException from an implementation other than the engine's
     */
    default Page<DecisionLogEntry> searchByCorrelationId(CorrelationSearch search, PageRequest page) {
        throw new UnsupportedOperationException("searchByCorrelationId is not supported by " + getClass().getName());
    }

    /**
     * Lists the decisions of one rule set whose input, stored in plain text, had exactly this value, in a period of
     * at most {@code query.max-range}, newest first (RLE-071). An input that is masked, by {@code mask: true} or by
     * the tenant's {@code decision-log.mask-default}, is refused rather than answered with no rows, so this search
     * cannot be used to test a masked value; {@link DecisionLogService#findByMaskedInput} is the search for those.
     *
     * @param search the rule set, input, value and period
     * @param page the page size and cursor; null for the first page
     * @return one page of decisions
     * @throws QueryInvalidException when the input is unknown or masked, the value has the wrong type, or the period,
     *     limit or cursor is invalid ({@code QUERY_INVALID})
     * @throws PermissionDeniedException when the caller lacks {@code rules:log:read}
     * @throws UnsupportedOperationException from an implementation other than the engine's
     */
    default Page<DecisionLogEntry> searchByInput(InputSearch search, PageRequest page) {
        throw new UnsupportedOperationException("searchByInput is not supported by " + getClass().getName());
    }
}

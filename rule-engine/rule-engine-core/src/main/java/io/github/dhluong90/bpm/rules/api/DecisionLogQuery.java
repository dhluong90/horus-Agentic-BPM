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
}

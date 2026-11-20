package io.github.dhluong90.bpm.process.api;

/**
 * Lists definitions and instances of the caller's tenant. Every list is a keyset page with a cursor signed for the
 * tenant and the list, at most {@code query.max-page-size} items. Safe for concurrent use. Reads only.
 */
public interface ProcessQueryService {

    /**
     * Lists the definition keys, ordered by key. Needs {@code processes:read}.
     *
     * @param page which page
     * @return one page
     * @throws QueryInvalidException when the limit or the cursor is not valid
     */
    Page<DefinitionSummary> definitions(PageRequest page);

    /**
     * Lists instances, newest first ({@code startedAt}, then {@code id}, both descending). Needs
     * {@code processes:read}.
     *
     * @param query the filters and the page
     * @return one page
     * @throws QueryInvalidException when the limit, the cursor, a status or the time range is not valid
     */
    Page<InstanceSummary> instances(InstanceQuery query);
}

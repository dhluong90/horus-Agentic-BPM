package io.github.dhluong90.bpm.process.api;

/**
 * Filter of the incident list of the caller's tenant (#68, PRE-080, PRE-522): newest first, keyset-paged.
 *
 * @param status {@code OPEN} (the default when null), {@code RESOLUTION_PENDING} or {@code RESOLVED}
 * @param page which page; at most {@code query.max-page-size} items
 * @param definitionKey keeps the incidents of the versions of this process key; null for every key
 * @param version keeps the incidents of this version number of {@code definitionKey}; null for every version; needs a
 *     {@code definitionKey}
 */
public record IncidentQuery(String status, PageRequest page, String definitionKey, Integer version) {

    public IncidentQuery {
        status = status == null ? Incident.OPEN : status;
        page = page == null ? PageRequest.first() : page;
    }

    /** The list of one status without a definition filter. */
    public IncidentQuery(String status, PageRequest page) {
        this(status, page, null, null);
    }

    /** @return the open incidents, first page */
    public static IncidentQuery open() {
        return new IncidentQuery(Incident.OPEN, PageRequest.first());
    }
}

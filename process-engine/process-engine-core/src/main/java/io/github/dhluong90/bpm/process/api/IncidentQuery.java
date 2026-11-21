package io.github.dhluong90.bpm.process.api;

/**
 * Filter of the incident list of the caller's tenant (#68, PRE-080): newest first, keyset-paged.
 *
 * @param status {@code OPEN} (the default when null), {@code RESOLUTION_PENDING} or {@code RESOLVED}
 * @param page which page; at most {@code query.max-page-size} items
 */
public record IncidentQuery(String status, PageRequest page) {

    public IncidentQuery {
        status = status == null ? Incident.OPEN : status;
        page = page == null ? PageRequest.first() : page;
    }

    /** @return the open incidents, first page */
    public static IncidentQuery open() {
        return new IncidentQuery(Incident.OPEN, PageRequest.first());
    }
}

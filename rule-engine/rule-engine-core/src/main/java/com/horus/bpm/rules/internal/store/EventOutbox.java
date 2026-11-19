package com.horus.bpm.rules.internal.store;

import java.time.Instant;

/** Writes outbox rows in the current transaction. The relay that delivers them is a later feature. */
public interface EventOutbox {

    /**
     * Appends one event.
     *
     * @param tenantId the tenant
     * @param id the event id (ULID)
     * @param payloadJson the event payload
     * @param createdAt the event time
     */
    void append(String tenantId, String id, String payloadJson, Instant createdAt);
}

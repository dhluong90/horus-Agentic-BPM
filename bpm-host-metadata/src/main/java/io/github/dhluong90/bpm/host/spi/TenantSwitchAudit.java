package io.github.dhluong90.bpm.host.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Receives one record for every accepted tenant switch (ADR-0014 decision 5, PD-037, #451): a caller who sent
 * {@code X-Tenant-Id} for another tenant in the verified allowed-tenants claim, and was let through. A refused
 * switch never reaches it. The engines' default writes the record as one {@code tenant.switch.accepted} line on
 * the {@code io.github.dhluong90.bpm.audit} logger at {@code INFO}; a host that keeps its audit trail elsewhere,
 * for example an append-only table or its SIEM, declares its own bean of this type, which replaces the default:
 * <pre>{@code
 * @Bean
 * TenantSwitchAudit hostTenantSwitchAudit(BankAuditTrail trail) {
 *     return record -> trail.append("tenant-switch", record.actor(), record.fromTenant(), record.toTenant(),
 *             record.at(), record.correlationId());
 * }
 * }</pre>
 *
 * <p>The record is written before the call reaches any engine data, once per request. An exception thrown here
 * fails the call, so no switched call runs without its record. The {@code tenant.switch.accepted} line on the
 * {@code io.github.dhluong90.bpm.security} logger is written as well, whichever sink is registered.
 *
 * <p>Implementations must be thread-safe, append only, and should not perform slow network I/O: they run on the
 * request thread.
 */
@FunctionalInterface
public interface TenantSwitchAudit {

    /**
     * Records one accepted switch.
     *
     * @param record who switched, from which tenant to which, when, and the correlation id of the call
     */
    void accepted(TenantSwitch record);

    /**
     * One accepted tenant switch.
     *
     * @param actor the signed-in user id
     * @param fromTenant the tenant the caller signed in for
     * @param toTenant the tenant the call is served for
     * @param at when the switch was accepted
     * @param correlationId the correlation id of the call, as in the security log and problem responses
     */
    record TenantSwitch(String actor, String fromTenant, String toTenant, Instant at, String correlationId) {

        public TenantSwitch {
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(fromTenant, "fromTenant must not be null");
            Objects.requireNonNull(toTenant, "toTenant must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }
    }
}

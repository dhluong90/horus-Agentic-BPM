package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.internal.CorrelationIds;
import io.github.dhluong90.bpm.host.spi.TenantSwitchAudit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The default {@link TenantSwitchAudit}: one {@code tenant.switch.accepted} line per accepted switch on the
 * {@code io.github.dhluong90.bpm.audit} logger at {@code INFO}, with the actor, both tenants, the time and the
 * correlation id. Values are encoded as on the security logger, so a value cannot forge another field.
 */
final class LoggingTenantSwitchAudit implements TenantSwitchAudit {

    static final String LOGGER = "io.github.dhluong90.bpm.audit";
    private static final Log AUDIT_LOG = LogFactory.getLog(LOGGER);

    @Override
    public void accepted(TenantSwitch record) {
        AUDIT_LOG.info("tenant.switch.accepted actor=" + CorrelationIds.loggable(record.actor())
                + " fromTenant=" + CorrelationIds.loggable(record.fromTenant())
                + " toTenant=" + CorrelationIds.loggable(record.toTenant())
                + " at=" + record.at()
                + " correlationId=" + record.correlationId());
    }

    @Override
    public String toString() {
        return "the engines' default tenant switch audit (" + LOGGER + " logger)";
    }
}

package com.horus.bpm.rules.autoconfigure;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the Rule Engine under {@code horus.bpm.rules}. Names follow the wiki page
 * "Configuration"; properties are added with the features that use them.
 *
 * @param enabled whether the Rule Engine is auto-configured at all
 * @param migrateOnStart whether the engine applies its own {@code rle_*} migrations at start-up on the
 *     host {@code DataSource}; {@code false} for hosts that run migrations in a separate job
 * @param security how host authorities map to engine permissions
 */
@ConfigurationProperties("horus.bpm.rules")
public record RuleEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean migrateOnStart,
        @DefaultValue Security security) {

    /**
     * Security settings.
     *
     * @param rolePermissions host authority, for example {@code ROLE_PRICING_APPROVER}, to the
     *     {@code rules:*} permissions it grants; empty when the host issues permission strings directly
     */
    public record Security(Map<String, List<String>> rolePermissions) {

        /** Copies the mapping; an unset mapping is empty. */
        public Security {
            rolePermissions = rolePermissions == null ? Map.of() : Map.copyOf(rolePermissions);
        }
    }
}

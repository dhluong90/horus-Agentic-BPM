package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import java.util.Set;

/**
 * The host's role list for approval (PRE-180, #449): the roles the demo users hold, in the {@code dev} tenant only, so a
 * definition whose escalation names another role is refused at approval and another tenant has no roles at all. A real
 * host asks its identity provider.
 */
final class DemoRoleCatalogue implements RoleCatalogue {

    /** The roles of the demo users of {@link HostSecurityConfiguration}. */
    static final Set<String> DEMO_ROLES = Set.of("PRICING_AUTHOR", "PRICING_APPROVER", "PROCESS_DEPLOYER",
            "PROCESS_APPROVER", "TELLER", "CARD_OFFICER", "BRANCH_SUPERVISOR");

    private final String tenant;

    DemoRoleCatalogue(String tenant) {
        this.tenant = tenant == null || tenant.isBlank() ? null : tenant;
    }

    @Override
    public boolean exists(String tenantId, String role) {
        return tenant != null && tenant.equals(tenantId) && role != null && DEMO_ROLES.contains(role);
    }
}

package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.host.spi.EngineAccess;
import com.horus.bpm.host.spi.EngineAccessDeniedException;
import com.horus.bpm.host.spi.EngineAccessDeniedException.Reason;
import com.horus.bpm.host.spi.EngineCaller;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The engine's caller and tenant come from the host contract's {@code EngineAccess}. Minh is a pricing
 * approver at bank-a.
 */
class EngineAccessResolversTest {

    private static final EngineCaller MINH =
            new EngineCaller("minh", null, "bank-a", Set.of("ROLE_PRICING_APPROVER"), List.of());

    private static EngineAccess granting(Set<String> granted) {
        return new EngineAccess() {
            @Override
            public EngineCaller currentCaller() {
                return MINH;
            }

            @Override
            public boolean hasPermission(EngineCaller caller, String permission) {
                return granted.contains(permission);
            }
        };
    }

    private static EngineAccess refusing(Reason reason) {
        return new EngineAccess() {
            @Override
            public EngineCaller currentCaller() {
                throw new EngineAccessDeniedException(reason, reason == Reason.TENANT_NOT_ALLOWED ? null
                        : "Bearer error=\"invalid_token\"", "01JD2X9ABCDEFGHJKMNPQRSTVW");
            }

            @Override
            public boolean hasPermission(EngineCaller caller, String permission) {
                return false;
            }
        };
    }

    @Test
    void RLE_009_engine_permissions_are_exactly_those_engine_access_grants() {
        CallerResolver callers = EngineAccessResolvers.callers(() -> granting(Set.of("rules:read", "rules:approve")));
        TenantResolver tenants = EngineAccessResolvers.tenants(() -> granting(Set.of()));

        assertThat(callers.currentCallerId()).isEqualTo("minh");
        assertThat(callers.currentPermissions()).containsExactlyInAnyOrder("rules:read", "rules:approve");
        assertThat(tenants.currentTenantId()).isEqualTo("bank-a");
    }

    @Test
    void RLE_037_no_principal_is_unauthenticated_but_tenant_refusals_reach_the_host_module() {
        // No authenticated principal: no caller, which the services answer with UNAUTHENTICATED
        assertThat(EngineAccessResolvers.callers(() -> refusing(Reason.UNAUTHENTICATED)).currentCallerId()).isNull();
        assertThat(EngineAccessResolvers.tenants(() -> refusing(Reason.UNAUTHENTICATED)).currentTenantId()).isNull();

        // No tenant keeps its invalid_token challenge, and a foreign tenant stays 403: both are rethrown
        for (Reason reason : List.of(Reason.NO_TENANT, Reason.TENANT_NOT_ALLOWED)) {
            assertThatThrownBy(() -> EngineAccessResolvers.tenants(() -> refusing(reason)).currentTenantId())
                    .isInstanceOfSatisfying(EngineAccessDeniedException.class,
                            refused -> assertThat(refused.reason()).isEqualTo(reason));
            assertThatThrownBy(() -> EngineAccessResolvers.callers(() -> refusing(reason)).currentPermissions())
                    .isInstanceOf(EngineAccessDeniedException.class);
        }
    }

    @Test
    void without_engine_access_every_call_is_refused() {
        assertThat(EngineAccessResolvers.callers(() -> null).currentCallerId()).isNull();
        assertThat(EngineAccessResolvers.callers(() -> null).currentPermissions()).isEmpty();
        assertThat(EngineAccessResolvers.tenants(() -> null).currentTenantId()).isNull();
    }
}

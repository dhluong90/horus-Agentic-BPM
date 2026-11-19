package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.host.spi.EngineAccess;
import com.horus.bpm.host.spi.EngineAccessDeniedException;
import com.horus.bpm.host.spi.EngineCaller;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The engine's caller and tenant from the host contract's {@link EngineAccess}, the same check
 * {@code GET /api/bpm/me} reports from, so what a client is shown is what the services allow.
 *
 * <p>No caller or no tenant is answered with {@code null}, which the services refuse with
 * {@code UNAUTHENTICATED}. A tenant the caller is not signed in for is not swallowed: the
 * {@link EngineAccessDeniedException} reaches the host module, which answers {@code 403 TENANT_NOT_ALLOWED}
 * with the same security log entry as {@code /me}. Without an {@code EngineAccess} bean (a host without
 * servlet or Spring Security) every call is refused. Thread-safe.
 */
final class EngineAccessResolvers {

    private EngineAccessResolvers() {
    }

    static CallerResolver callers(Supplier<@Nullable EngineAccess> access) {
        return new CallerResolver() {
            @Override
            public String currentCallerId() {
                EngineCaller caller = caller(access);
                return caller == null ? null : caller.userId();
            }

            @Override
            public Set<String> currentPermissions() {
                EngineAccess engineAccess = access.get();
                EngineCaller caller = caller(access);
                if (engineAccess == null || caller == null) {
                    return Set.of();
                }
                return RuleEngineModule.PERMISSIONS.stream()
                        .filter(permission -> engineAccess.hasPermission(caller, permission))
                        .collect(Collectors.toUnmodifiableSet());
            }
        };
    }

    static TenantResolver tenants(Supplier<@Nullable EngineAccess> access) {
        return () -> {
            EngineCaller caller = caller(access);
            return caller == null ? null : caller.tenant();
        };
    }

    /** The current caller, or {@code null} when there is none; a refused tenant is rethrown. */
    private static @Nullable EngineCaller caller(Supplier<@Nullable EngineAccess> access) {
        EngineAccess engineAccess = access.get();
        if (engineAccess == null) {
            return null;
        }
        try {
            return engineAccess.currentCaller();
        } catch (EngineAccessDeniedException refused) {
            if (refused.reason() == EngineAccessDeniedException.Reason.TENANT_NOT_ALLOWED) {
                throw refused;
            }
            return null;
        }
    }
}

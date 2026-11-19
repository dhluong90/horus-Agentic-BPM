package io.github.dhluong90.bpm.process.autoconfigure;

import com.horus.bpm.host.spi.EngineAccess;
import com.horus.bpm.host.spi.EngineAccessDeniedException;
import com.horus.bpm.host.spi.EngineCaller;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The default caller and tenant of the Process Engine, delegated to the host contract's {@link EngineAccess}, so
 * the engine allows exactly what {@code /api/bpm/me} shows: one tenant resolution, one role-to-permission mapping
 * for both engines. Roles are the caller's {@code ROLE_*} authorities without the prefix.
 */
final class EngineAccessResolvers {

    private EngineAccessResolvers() {
    }

    static CallerResolver callers(Supplier<EngineAccess> access) {
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
                return ProcessEngineAutoConfiguration.PERMISSIONS.stream()
                        .filter(permission -> engineAccess.hasPermission(caller, permission))
                        .collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public Set<String> currentRoles() {
                EngineCaller caller = caller(access);
                return caller == null ? Set.of() : caller.authorities().stream().filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.substring(5)).collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public Set<String> currentGroups() {
                EngineCaller caller = caller(access);
                return caller == null ? Set.of() : Set.copyOf(caller.groups());
            }
        };
    }

    static TenantResolver tenants(Supplier<EngineAccess> access) {
        return () -> {
            EngineCaller caller = caller(access);
            return caller == null ? null : caller.tenant();
        };
    }

    private static EngineCaller caller(Supplier<EngineAccess> access) {
        EngineAccess engineAccess = access.get();
        if (engineAccess == null) {
            return null;
        }
        try {
            return engineAccess.currentCaller();
        } catch (EngineAccessDeniedException refused) {
            if (refused.reason() == EngineAccessDeniedException.Reason.UNAUTHENTICATED) {
                return null;
            }
            throw refused;
        }
    }
}

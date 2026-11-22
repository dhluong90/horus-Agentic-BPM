package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import io.github.dhluong90.bpm.host.spi.EngineCaller;
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
        return callers(access, () -> null);
    }

    /**
     * @param names the host's display names (#442), or a supplier of null when there are none
     */
    static CallerResolver callers(Supplier<EngineAccess> access,
            Supplier<io.github.dhluong90.bpm.host.spi.DisplayNames> names) {
        return new CallerResolver() {
            /** #442: the host's {@code DisplayNames}, in the caller's tenant and the request's language. */
            @Override
            public java.util.Optional<String> displayNameOf(String userId) {
                io.github.dhluong90.bpm.host.spi.DisplayNames directory = names.get();
                EngineCaller caller = caller(access);
                if (directory == null || caller == null || userId == null) {
                    return java.util.Optional.empty();
                }
                return directory.user(caller.tenant(), userId,
                        org.springframework.context.i18n.LocaleContextHolder.getLocale());
            }

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

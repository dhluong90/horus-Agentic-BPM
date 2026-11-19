package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.HorusBpmException;
import com.horus.bpm.rules.api.IdempotencyInFlightException;
import com.horus.bpm.rules.api.IdempotencyKeyReuseException;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.PermissionDeniedException;
import com.horus.bpm.rules.api.UnauthenticatedException;
import com.horus.bpm.rules.internal.store.IdempotencyStore;
import com.horus.bpm.rules.internal.store.TransactionRunner;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What both services share: tenant and caller resolution, the clock, transactions and idempotency.
 *
 * @param tenants resolves the tenant from the host security context
 * @param callers resolves the caller
 * @param clock the host clock
 * @param transactions joins or opens a transaction
 * @param idempotency idempotency records
 * @param settings validated engine settings
 */
public record EngineContext(
        TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
        IdempotencyStore idempotency, EngineSettings settings) {

    /** Longest idempotency key accepted. */
    static final int MAX_KEY_LENGTH = 200;

    public EngineContext {
        Objects.requireNonNull(tenants, "tenants must not be null");
        Objects.requireNonNull(callers, "callers must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactions, "transactions must not be null");
        Objects.requireNonNull(idempotency, "idempotency must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
    }

    String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    /**
     * Refuses the call unless the caller holds a permission (ADR-0010). Checked in the services, so a host that
     * calls the Java API directly gets the same guard as the REST endpoints.
     */
    String callerWith(String permission) {
        String caller = caller();
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
        return caller;
    }

    String caller() {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        return caller;
    }

    /**
     * Runs a write in a transaction; with a key, a replay returns the recorded result (ADR-0006).
     */
    <T> T idempotent(String tenant, String operation, String key, String requestHash, int statusCode,
            Supplier<T> action, Function<T, String> toJson, Function<String, T> fromJson) {
        if (key != null && (key.isBlank() || key.length() > MAX_KEY_LENGTH)) {
            throw new InputInvalidException("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        return withoutDooming(() -> {
            if (key == null) {
                return action.get();
            }
            var now = clock.instant();
            return switch (idempotency.claim(tenant, operation, key, requestHash, now)) {
                case IdempotencyStore.Replay replay -> fromJson.apply(replay.responseJson());
                case IdempotencyStore.Mismatch ignored -> throw new IdempotencyKeyReuseException();
                case IdempotencyStore.InFlight ignored -> throw new IdempotencyInFlightException();
                case IdempotencyStore.Fresh ignored -> {
                    T result = action.get();
                    idempotency.record(tenant, operation, key, requestHash, statusCode, toJson.apply(result),
                            now.plus(settings.idempotencyRetention()));
                    yield result;
                }
            };
        });
    }

    /**
     * Runs work in the caller's transaction, but returns an engine refusal ({@link HorusBpmException}) as a value
     * out of the transaction callback and throws it afterwards, so a refusal the host catches never marks the
     * host's transaction rollback-only (TR-EMB-04). Every refusal happens before the engine writes anything that
     * matters; unexpected exceptions still roll back.
     */
    <T> T withoutDooming(Supplier<T> work) {
        Result<T> result = transactions.inTransaction(() -> {
            try {
                return new Result<>(work.get(), null);
            } catch (HorusBpmException refusal) {
                return new Result<>(null, refusal);
            }
        });
        if (result.refusal() != null) {
            throw result.refusal();
        }
        return result.value();
    }

    private record Result<T>(T value, HorusBpmException refusal) {
    }
}

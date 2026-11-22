package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.IdempotencyRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The idempotency of a write command (#90, #102): the first call with a key does the work and stores its answer in the
 * same transaction; a repeat with the same key and request returns that answer and writes nothing; the same key with
 * another request is refused. Must run inside the caller's transaction. A call without a key just runs.
 */
final class IdempotentCalls {

    private final ProcessStore store;
    private final Clock clock;
    private final Duration retention;

    IdempotentCalls(ProcessStore store, Clock clock, Duration retention) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    <T> T run(String tenant, String operation, String key, String requestHash, Supplier<T> action,
            Function<T, String> toJson, Function<String, T> fromJson) {
        if (key == null) {
            return action.get();
        }
        if (key.isBlank() || key.length() > EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InputInvalidException("Idempotency-Key must be 1 to " + EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH
                    + " characters");
        }
        Instant now = clock.instant();
        // Claim first: a concurrent request with the same key blocks here until this transaction ends, then replays.
        if (store.claimIdempotency(tenant, operation, key, requestHash, now.plus(retention), now)) {
            T result = action.get();
            store.completeIdempotency(tenant, operation, key, toJson.apply(result));
            return result;
        }
        IdempotencyRecord previous = store.idempotency(tenant, operation, key, now)
                .orElseThrow(() -> new IdempotencyInFlightException("A request with this Idempotency-Key is running"));
        if (!previous.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException("The Idempotency-Key was used with a different request");
        }
        if (previous.responseJson() == null) {
            throw new IdempotencyInFlightException("A request with this Idempotency-Key is running");
        }
        return fromJson.apply(previous.responseJson());
    }
}

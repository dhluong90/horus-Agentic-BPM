package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The wait of a second request on an idempotency claim (#172, PRE-378). The claim insert blocks on the primary key
 * until the first request commits and a pool connection is held meanwhile, so the wait is capped with
 * {@code lock_timeout}, set for the claim statement only: the work after the claim keeps the session's own timeout.
 * A request that waits the whole time gets {@code IDEMPOTENCY_IN_FLIGHT}; the client retries after one second.
 */
final class IdempotencyClaim {

    /** SQLSTATE of {@code lock_not_available}, raised when {@code lock_timeout} expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private IdempotencyClaim() {
    }

    /**
     * Runs the claim statement with the timeout.
     *
     * @param jdbc the client on the caller's transaction
     * @param lockTimeout the longest wait
     * @param claim the claim statement; true when this transaction holds the claim
     * @return what the statement returned
     * @throws IdempotencyInFlightException when the first request still holds the key after the timeout
     */
    static boolean run(JdbcClient jdbc, Duration lockTimeout, BooleanSupplier claim) {
        String before = jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
        jdbc.sql("SELECT set_config('lock_timeout', ?, true)").param(Math.max(1, lockTimeout.toMillis()) + "ms")
                .query(String.class).single();
        boolean claimed;
        try {
            claimed = claim.getAsBoolean();
        } catch (RuntimeException e) {
            if (lockNotAvailable(e)) {
                throw new IdempotencyInFlightException("A request with this Idempotency-Key is running");
            }
            throw e;
        }
        jdbc.sql("SELECT set_config('lock_timeout', ?, true)").param(before).query(String.class).single();
        return claimed;
    }

    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}

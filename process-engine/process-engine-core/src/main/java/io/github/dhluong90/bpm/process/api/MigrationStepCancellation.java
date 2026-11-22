package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * A removed step of a migration plan (#106, PRE-201): the work waiting at {@code step} in the source version is
 * cancelled for the reason given, and the token continues at {@code moveTo} in the target version, which runs as on
 * arrival.
 *
 * @param step the step id in the source version, which the target version no longer has
 * @param moveTo the step id in the target version where the token continues
 * @param reason why, at most {@code lifecycle.reason-max-length} characters; written to the append-only history
 */
public record MigrationStepCancellation(String step, String moveTo, String reason) {

    public MigrationStepCancellation {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(moveTo, "moveTo");
        Objects.requireNonNull(reason, "reason");
    }
}

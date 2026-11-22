package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * A renamed step of a migration plan (#106, PRE-207): the tokens, open tasks and pending jobs waiting at {@code from}
 * in the source version move to {@code to} in the target version. The two steps must be of the same type; a task keeps
 * its id, claim, due time and four-eyes restriction.
 *
 * @param from the step id in the source version
 * @param to the step id in the target version
 */
public record MigrationStepMapping(String from, String to) {

    public MigrationStepMapping {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}

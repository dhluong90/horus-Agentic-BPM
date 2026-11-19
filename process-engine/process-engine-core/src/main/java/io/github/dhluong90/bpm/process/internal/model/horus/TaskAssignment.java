package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:assignee} and {@code horus:fourEyes} of a user task; the assignee is {@code user:<id>}, {@code role:<role>} or {@code group:<group>}.
 *
 * @param assignee assignee
 * @param fourEyes fourEyes
 */
public record TaskAssignment(
        String assignee,
        boolean fourEyes) implements HorusExtension {
}

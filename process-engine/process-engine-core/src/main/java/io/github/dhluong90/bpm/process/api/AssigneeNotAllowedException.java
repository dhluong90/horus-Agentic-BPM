package io.github.dhluong90.bpm.process.api;

/**
 * The person a task would be handed to may not work it: they lack the task's role or group, they started the
 * instance or completed an earlier task of it on a four-eyes task, or they already hold the task. Code
 * {@code ASSIGNEE_NOT_ALLOWED} (PD-110). {@link #reason()} says which rule refused (#442), and {@link #required()}
 * names the role or group the target lacks, so a client can show its readable name.
 */
public final class AssigneeNotAllowedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** The target's id starts with the engines' reserved {@code system:} prefix. */
    public static final String RESERVED_IDENTITY = "RESERVED_IDENTITY";
    /** The target already holds the task. */
    public static final String ALREADY_ASSIGNED = "ALREADY_ASSIGNED";
    /** The target started the instance or completed an earlier task of it, and the task is four-eyes. */
    public static final String FOUR_EYES_PARTICIPANT = "FOUR_EYES_PARTICIPANT";
    /** The target does not hold the role the task is assigned to; {@link #required()} is {@code role:<id>}. */
    public static final String MISSING_ROLE = "MISSING_ROLE";
    /** The target is not in the group the task is assigned to; {@link #required()} is {@code group:<id>}. */
    public static final String MISSING_GROUP = "MISSING_GROUP";

    private final String reason;
    private final String required;

    /** @param message the detail */
    public AssigneeNotAllowedException(String message) {
        this(null, null, message);
    }

    /**
     * @param reason which rule refused, one of the constants of this class, or null
     * @param required the role or group the target lacks, as {@code role:<id>} or {@code group:<id>}, or null
     * @param message the detail
     */
    public AssigneeNotAllowedException(String reason, String required, String message) {
        super("ASSIGNEE_NOT_ALLOWED", message);
        this.reason = reason;
        this.required = required;
    }

    /** @return which rule refused, or null when not known */
    public String reason() {
        return reason;
    }

    /** @return the role or group the target lacks ({@code role:<id>} or {@code group:<id>}), or null */
    public String required() {
        return required;
    }
}

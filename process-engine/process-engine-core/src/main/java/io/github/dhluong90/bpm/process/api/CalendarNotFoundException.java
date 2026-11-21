package io.github.dhluong90.bpm.process.api;

/**
 * No business calendar under this key in the tenant, or no change with this id. Code {@code CALENDAR_NOT_FOUND}.
 * When a user task names such a calendar, the step fails ({@code STEP_FAILED} with this reason) and nothing is
 * written.
 */
public final class CalendarNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public CalendarNotFoundException(String message) {
        super("CALENDAR_NOT_FOUND", message);
    }
}

package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 403 CLOCK_NOT_ALLOWED}: the request chose its own clock, which only {@code rules:simulate} may do, because a
 * chosen instant reaches versions and dates the caller would not otherwise get.
 */
public final class ClockNotAllowedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ClockNotAllowedException() {
        super("CLOCK_NOT_ALLOWED", "Choosing the evaluation clock requires rules:simulate; leave clock out to use"
                + " the host clock");
    }
}

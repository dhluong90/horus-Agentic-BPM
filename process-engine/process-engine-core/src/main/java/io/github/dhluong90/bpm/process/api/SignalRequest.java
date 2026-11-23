package io.github.dhluong90.bpm.process.api;

/**
 * A request to broadcast a signal.
 *
 * @param name the signal name: 1 to 100 letters, digits, {@code .}, {@code _}, {@code :} or {@code -}
 * @param idempotencyKey the key that makes a retry return the first answer; required
 */
public record SignalRequest(String name, String idempotencyKey) {

    /** What a signal name looks like, for a signal event and for a broadcast alike. */
    public static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$");
}

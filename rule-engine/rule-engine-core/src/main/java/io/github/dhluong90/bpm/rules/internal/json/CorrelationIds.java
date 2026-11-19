package io.github.dhluong90.bpm.rules.internal.json;

import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import java.util.regex.Pattern;

/** The accepted form of a correlation id: 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}. */
public final class CorrelationIds {

    private static final Pattern VALID = Pattern.compile(
            "[A-Za-z0-9._:-]{1," + EngineDefaults.CORRELATION_ID_MAX_LENGTH + "}");

    private CorrelationIds() {
    }

    /**
     * Tells whether a correlation id may be stored and echoed.
     *
     * @param id the id, possibly null
     * @return true when it is non-null and well-formed
     */
    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }
}

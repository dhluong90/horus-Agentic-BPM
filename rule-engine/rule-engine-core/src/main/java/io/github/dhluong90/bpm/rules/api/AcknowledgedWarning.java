package io.github.dhluong90.bpm.rules.api;

import java.util.Objects;

/**
 * One warning an approver accepted on the record when approving a version (RLE-053, PD-104), stored on the
 * {@code APPROVED} history row and in {@code rules.version.published.v1}.
 *
 * @param code the warning code, for example {@code UNMASKED_PERSONAL_DATA}
 * @param input the input it is about, for example {@code nationalId}
 */
public record AcknowledgedWarning(String code, String input) implements java.io.Serializable {

    public AcknowledgedWarning {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(input, "input must not be null");
    }
}

package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The row-level difference between two versions of one rule set, computed by the engine from their canonical models
 * (DESIGN section 4.5, RLE-028, RLE-048). Source text, comments and {@code effectiveFrom} are never content changes.
 *
 * @param ruleSet the rule set name
 * @param version the version compared
 * @param base the version it is compared against
 * @param canonicalHash the canonical hash of {@code version}
 * @param baseCanonicalHash the canonical hash of {@code base}
 * @param sourceHashChanged whether the source documents differ
 * @param effectiveFrom the effective date of {@code version}, or null
 * @param baseEffectiveFrom the effective date of {@code base}, or null
 * @param header changed document members other than columns and rules, in canonical order
 * @param inputs changes to the input columns
 * @param outputs changes to the output columns
 * @param rows changed rows, by position in {@code version}, then removed rows by position in {@code base}
 * @param unchangedCount rows present in both with the same cells and not moved
 * @param masking the masking status of every input of either version, in the order of {@code version} then the
 *     inputs only in {@code base}, with changes marked (RLE-051, PD-099 mitigation 3)
 */
public record VersionDiff(String ruleSet, int version, int base, String canonicalHash, String baseCanonicalHash,
        boolean sourceHashChanged, Instant effectiveFrom, Instant baseEffectiveFrom, List<FieldChange> header,
        ColumnChanges inputs, ColumnChanges outputs, List<RowChange> rows, int unchangedCount,
        List<InputMasking> masking) {

    public VersionDiff {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        header = List.copyOf(header);
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(outputs, "outputs must not be null");
        rows = List.copyOf(rows);
        masking = masking == null ? List.of() : List.copyOf(masking);
    }

    /**
     * Creates a diff without masking statuses.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param base see the record
     * @param canonicalHash see the record
     * @param baseCanonicalHash see the record
     * @param sourceHashChanged see the record
     * @param effectiveFrom see the record
     * @param baseEffectiveFrom see the record
     * @param header see the record
     * @param inputs see the record
     * @param outputs see the record
     * @param rows see the record
     * @param unchangedCount see the record
     */
    public VersionDiff(String ruleSet, int version, int base, String canonicalHash, String baseCanonicalHash,
            boolean sourceHashChanged, Instant effectiveFrom, Instant baseEffectiveFrom, List<FieldChange> header,
            ColumnChanges inputs, ColumnChanges outputs, List<RowChange> rows, int unchangedCount) {
        this(ruleSet, version, base, canonicalHash, baseCanonicalHash, sourceHashChanged, effectiveFrom,
                baseEffectiveFrom, header, inputs, outputs, rows, unchangedCount, List.of());
    }

    /**
     * Whether an input's values are masked in the decision log, and why: by its own declaration or by the tenant's
     * {@code decision-log.mask-default} in force when the diff is read.
     */
    public enum Masking {
        /** Declares {@code mask: true}. */
        MASKED,
        /** Declares {@code mask: false}. */
        NOT_MASKED,
        /** Declares no {@code mask}; the tenant masks undeclared inputs. */
        MASKED_BY_DEFAULT,
        /** Declares no {@code mask}; the tenant does not mask undeclared inputs. */
        NOT_MASKED_BY_DEFAULT;

        /**
         * Tells whether values are masked.
         *
         * @return true for {@link #MASKED} and {@link #MASKED_BY_DEFAULT}
         */
        public boolean masked() {
            return this == MASKED || this == MASKED_BY_DEFAULT;
        }

        /**
         * Tells whether the input declares {@code mask} itself.
         *
         * @return true for {@link #MASKED} and {@link #NOT_MASKED}
         */
        public boolean declared() {
            return this == MASKED || this == NOT_MASKED;
        }
    }

    /**
     * The masking status of one input in both versions.
     *
     * @param input the input name
     * @param from its status in {@code base}, or null when it is only in {@code version}
     * @param to its status in {@code version}, or null when it is only in {@code base}
     * @param changed whether the status differs, so the approver sees "masked to not masked" before approving
     * @param personalDataName when the input declares no {@code mask} in {@code version} and its name looks like
     *     personal data, the configured name it matched (RLE-049); otherwise null
     */
    public record InputMasking(String input, Masking from, Masking to, boolean changed, String personalDataName) {
        public InputMasking {
            Objects.requireNonNull(input, "input must not be null");
        }
    }

    /** How a row differs. */
    public enum Change {
        /** Only in {@code version}. */
        ADDED,
        /** Only in {@code base}. */
        REMOVED,
        /** In both, with different cells. */
        CHANGED,
        /** In both, with the same cells, at a different place relative to the other common rows. */
        MOVED
    }

    /**
     * One changed member.
     *
     * @param path JSON Pointer of the member, relative to the row, the column or the document
     * @param from the value in {@code base} as canonical JSON text (RFC 8785), or null when absent there
     * @param to the value in {@code version} as canonical JSON text, or null when absent there
     * @param nonAscii whether either value's text holds a character outside printable ASCII or an escaped
     *     character such as a zero-width space (ADR-0026 decision 6)
     */
    public record FieldChange(String path, String from, String to, boolean nonAscii) {
        public FieldChange {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    /**
     * One changed column.
     *
     * @param name the column name
     * @param fields its changed members
     */
    public record ColumnChange(String name, List<FieldChange> fields) {
        public ColumnChange {
            Objects.requireNonNull(name, "name must not be null");
            fields = List.copyOf(fields);
        }
    }

    /**
     * Changes to the input or output columns, matched by name.
     *
     * @param added names only in {@code version}, in its order
     * @param removed names only in {@code base}, in its order
     * @param changed common columns with changed members, in the order of {@code version}
     * @param reordered whether the common columns are in a different order
     */
    public record ColumnChanges(List<String> added, List<String> removed, List<ColumnChange> changed,
            boolean reordered) {
        public ColumnChanges {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            changed = List.copyOf(changed);
        }
    }

    /**
     * One changed row, matched by rule id.
     *
     * @param ruleId the rule id
     * @param change how it differs
     * @param position 1-based place in {@code version}, or null when removed
     * @param basePosition 1-based place in {@code base}, or null when added
     * @param moved on {@code CHANGED} only: whether the row also moved
     * @param fields on {@code CHANGED} only: the changed cells and members, in canonical order
     */
    public record RowChange(String ruleId, Change change, Integer position, Integer basePosition, boolean moved,
            List<FieldChange> fields) {
        public RowChange {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            Objects.requireNonNull(change, "change must not be null");
            fields = List.copyOf(fields);
        }
    }
}

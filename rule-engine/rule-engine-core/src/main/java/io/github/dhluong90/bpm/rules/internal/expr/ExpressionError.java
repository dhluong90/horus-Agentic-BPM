package io.github.dhluong90.bpm.rules.internal.expr;

/**
 * Why an expression is refused (RLE-004). The name is the stable {@code code} of a {@code VALIDATION_FAILED}
 * violation; the message beside it is for people and may change.
 */
public enum ExpressionError {

    /** The text is not an expression of the language: a missing parenthesis, a stray word, an unknown escape. */
    SYNTAX_ERROR,

    /** A name that is not a declared input of the rule set, for example {@code creditScoreX}. */
    UNKNOWN_INPUT,

    /**
     * Text that tries to reach outside the language: a call of a function the language does not have
     * ({@code T(java.lang.Runtime)}), member access with {@code .}, or a statement character such as {@code ;}.
     * Refused before anything inside it is read, bound or evaluated (TR-SEC-01).
     */
    FORBIDDEN_CONSTRUCT,

    /** Operands of the wrong types, for example {@code money + number}, or a condition that is not true or false. */
    TYPE_MISMATCH,

    /** A malformed literal: a date, a datetime, a money amount or a {@code round} scale. */
    INVALID_LITERAL,

    /** A {@code matches} pattern that is too long, outside the supported dialect or not a regular expression. */
    INVALID_PATTERN,

    /** An expression longer than {@link Parser#MAX_LENGTH} characters. */
    EXPRESSION_TOO_LONG,

    /** An expression nested deeper than {@link Parser#MAX_DEPTH}. */
    EXPRESSION_TOO_DEEP
}

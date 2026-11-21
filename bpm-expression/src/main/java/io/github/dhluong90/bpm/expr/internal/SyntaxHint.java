package io.github.dhluong90.bpm.expr.internal;

/**
 * What a {@link ExpressionError#SYNTAX_ERROR} expected at its position (#419). The hint is a fixed kind, never text of
 * the expression, so a sentence chosen by it cannot echo a masked value. A host or screen words each kind from a
 * message catalogue: the token that was expected and a short example.
 */
public enum SyntaxHint {

    /** A value was expected: a number, a quoted text, an input name, a function or a parenthesis. */
    OPERAND,

    /** The expression is complete but more text follows: an operator, {@code and}, {@code or} or the end. */
    OPERATOR_OR_END,

    /** An opening parenthesis {@code (} after a function name. */
    OPEN_PAREN,

    /** A closing parenthesis {@code )}. */
    CLOSE_PAREN,

    /** An opening bracket {@code [} of a list after {@code in}. */
    OPEN_BRACKET,

    /** A closing bracket {@code ]} of a list. */
    CLOSE_BRACKET,

    /** The word {@code null} after {@code is} or {@code is not}. */
    KEYWORD_NULL,

    /** The word {@code and} between the two limits of {@code between}. */
    KEYWORD_AND,

    /** A text written with a closing single quote. */
    UNTERMINATED_STRING,

    /** A text written in single quotes, not double quotes. */
    SINGLE_QUOTES,

    /** Equality written {@code ==}. */
    EQUALITY,

    /** Logic written with the words {@code and}, {@code or} and {@code not}. */
    LOGIC_WORDS,

    /** A number that starts with a digit. */
    NUMBER_START,

    /** A number written with the digits 0 to 9 only. */
    NUMBER_DIGITS,

    /** A text escape that is one of the three allowed forms. */
    STRING_ESCAPE,

    /** A function call with the right form: one text literal, or the pattern as a text literal. */
    LITERAL_ARGUMENT,

    /** A character the language does not use. */
    CHARACTER
}

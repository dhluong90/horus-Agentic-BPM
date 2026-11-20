package io.github.dhluong90.bpm.expr.spi;

import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;

/** One expression language, found by its {@link #id()}. Immutable and thread-safe. */
public interface ExpressionLanguage {

    /** @return the language id, for example {@code urn:horus-bpm:expr:v1} */
    String id();

    /**
     * Compiles a text once, at import or publication; never on the evaluation path.
     *
     * @param source the text
     * @param types the names the text may read
     * @return the compiled expression
     * @throws ExpressionInvalidException with the position when the text does not compile
     */
    CompiledExpression compile(String source, TypeEnvironment types);
}

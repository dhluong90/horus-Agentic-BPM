/**
 * The expression language extension point (ADR-0027, rule engine DESIGN section 3.5). Both engines compile and
 * evaluate expressions only through {@link io.github.dhluong90.bpm.expr.spi.ExpressionLanguage}, so a second language
 * registers beside {@code urn:horus-bpm:expr:v1} without either engine depending on parser classes. Implementations
 * are immutable and thread-safe; a compiled expression may be shared by threads.
 */
package io.github.dhluong90.bpm.expr.spi;

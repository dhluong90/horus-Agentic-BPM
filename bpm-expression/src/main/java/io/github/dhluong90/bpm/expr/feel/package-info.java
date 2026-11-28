/**
 * The FEEL contract of ADR-0028: syntax tree, types, parser, engine, built-ins and printer, as interfaces and
 * stubs. Engine-to-engine surface, not a host API and not part of the 1.x compatibility promise until the 1.2 gate:
 * hosts reach FEEL only through {@link io.github.dhluong90.bpm.expr.spi.ExpressionLanguage}. Changes here are
 * additive and go through a pull request labelled {@code contract-change}.
 */
package io.github.dhluong90.bpm.expr.feel;

/**
 * The {@code urn:horus-bpm:expr:v1} parser, tree, evaluator and bounded regular expressions, and the language that
 * exposes them through the SPI. Shared by the two engines only: the Rule Engine compiles decision tables from the tree
 * directly; the Process Engine uses the SPI. No compatibility promise: hosts use {@code expr.api} and {@code expr.spi}.
 */
package io.github.dhluong90.bpm.expr.internal;

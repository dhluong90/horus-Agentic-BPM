package io.github.dhluong90.bpm.expr.spi;

/** How an expression runs: {@link #COMPILED} on slots resolved at compile time; {@link #GENERAL} for full FEEL. */
public enum EvaluationProfile {
    COMPILED, GENERAL
}

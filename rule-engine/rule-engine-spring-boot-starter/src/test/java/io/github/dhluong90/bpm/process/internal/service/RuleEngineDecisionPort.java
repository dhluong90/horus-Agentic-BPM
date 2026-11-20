package io.github.dhluong90.bpm.process.internal.service;

/**
 * A stand-in with the name of the Process Engine's decision port, so that the Rule Engine starter's tests run with the
 * grant registered (#336). The Process Engine is not on this module's class path; the real class never meets this one.
 */
public final class RuleEngineDecisionPort {
}

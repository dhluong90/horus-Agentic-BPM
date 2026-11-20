package io.github.dhluong90.bpm.process.internal.service;

/**
 * Host code placed in the Process Engine's core package to reach its package-private members (#336, security review
 * on #351): it builds an engine caller, which only the engine's timer path may. It compiles, but the package is
 * sealed, so the JVM refuses to load it next to the engine's jar. Not a test: the tests load it on purpose and expect
 * the refusal.
 */
public final class SplitPackageAttacker {

    private SplitPackageAttacker() {
    }

    /** @return an engine caller, if the attack ever ran */
    public static Object attack() {
        return new DecisionCaller.Engine("system:timer");
    }
}

package io.github.dhluong90.bpm.examples.ruleandprocess;

/**
 * Started inside the repackaged sample host by Spring Boot's {@code PropertiesLauncher} (#336): it loads engine
 * classes from the nested engine jars, then the split-package attacker from outside them, and prints what the JVM
 * did. Not a test; {@link SealedPackagesFatJarIT} runs it.
 */
public final class SealedPackageProbe {

    static final String ATTACKER = "io.github.dhluong90.bpm.process.internal.service.SplitPackageAttacker";
    static final String CLAIMANT_ATTACKER = "io.github.dhluong90.bpm.process.autoconfigure.ClaimantSplitPackageAttacker";

    private SealedPackageProbe() {
    }

    public static void main(String[] args) {
        ClassLoader loader = SealedPackageProbe.class.getClassLoader();
        try {
            Class<?> caller = Class.forName("io.github.dhluong90.bpm.process.internal.service.DecisionCaller", false,
                    loader);
            Class<?> port = Class.forName("io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort",
                    false, loader);
            Class<?> engines = Class.forName("io.github.dhluong90.bpm.rules.internal.service.RuleEngines", false,
                    loader);
            Class<?> grant = Class.forName("io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants",
                    false, loader);
            System.out.println("ENGINE_LOADED processSealed=" + caller.getPackage().isSealed() + " rulesSealed="
                    + engines.getPackage().isSealed() + " starterSealed=" + port.getPackage().isSealed() + " from="
                    + port.getProtectionDomain().getCodeSource().getLocation() + " grant=" + grant.getSimpleName());
        } catch (Throwable e) {
            System.out.println("ENGINE_FAILED " + e);
            return;
        }
        probe("ATTACKER", ATTACKER, loader);
        probe("CLAIMANT_ATTACKER", CLAIMANT_ATTACKER, loader);
    }

    private static void probe(String label, String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            System.out.println(label + "_LOADED");
        } catch (SecurityException e) {
            System.out.println(label + "_REFUSED " + e.getMessage());
        } catch (Throwable e) {
            System.out.println(label + "_OTHER " + e);
        }
    }
}

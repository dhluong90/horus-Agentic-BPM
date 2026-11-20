package io.github.dhluong90.bpm.examples.ruleandprocess;

/**
 * Started inside the repackaged sample host by Spring Boot's {@code PropertiesLauncher} (#336): it loads engine
 * classes from the nested engine jars, then the split-package attacker from outside them, and prints what the JVM
 * did. Not a test; {@link SealedPackagesFatJarIT} runs it.
 */
public final class SealedPackageProbe {

    static final String ATTACKER = "io.github.dhluong90.bpm.process.internal.service.SplitPackageAttacker";

    private SealedPackageProbe() {
    }

    public static void main(String[] args) {
        ClassLoader loader = SealedPackageProbe.class.getClassLoader();
        try {
            Class<?> caller = Class.forName("io.github.dhluong90.bpm.process.internal.service.DecisionCaller", false,
                    loader);
            Class<?> port = Class.forName("io.github.dhluong90.bpm.process.internal.service.RuleEngineDecisionPort",
                    false, loader);
            Class<?> engines = Class.forName("io.github.dhluong90.bpm.rules.internal.service.RuleEngines", false,
                    loader);
            Class<?> grant = Class.forName("io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants",
                    false, loader);
            System.out.println("ENGINE_LOADED processSealed=" + caller.getPackage().isSealed() + " rulesSealed="
                    + engines.getPackage().isSealed() + " from=" + port.getProtectionDomain().getCodeSource()
                    .getLocation() + " grant=" + grant.getSimpleName());
        } catch (Throwable e) {
            System.out.println("ENGINE_FAILED " + e);
            return;
        }
        try {
            Class.forName(ATTACKER, false, loader);
            System.out.println("ATTACKER_LOADED");
        } catch (SecurityException e) {
            System.out.println("ATTACKER_REFUSED " + e.getMessage());
        } catch (Throwable e) {
            System.out.println("ATTACKER_OTHER " + e);
        }
    }
}

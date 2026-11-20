package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The claimant check of the tenant-scoped entry point (#336): the registered claimant is granted only when its class is
 * the class resolved for the registered name, its package is sealed, and its jar carries the Rule Engine's
 * {@code Implementation-Version}. The claimant is compiled here and packed into jars with chosen manifests, in a
 * package this test's class path does not contain, so it is visible only through the loader the test sets.
 */
class EmbeddedEngineClaimantVerificationTest {

    static final String PORT = "io.github.dhluong90.bpm.fixture.decisionport.DecisionPortStandIn";
    static final String ENGINE_VERSION = "1.4.0";

    @TempDir
    static Path dir;
    static Path classes;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    @BeforeAll
    static void compileTheStandIn() throws IOException {
        Path source = dir.resolve("src/io/github/dhluong90/bpm/fixture/decisionport/DecisionPortStandIn.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package io.github.dhluong90.bpm.fixture.decisionport;
                public final class DecisionPortStandIn {
                    public DecisionPortStandIn() {
                    }
                }
                """);
        classes = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "-d", classes.toString(), source.toString())).isZero();
    }

    /** A grant whose registered claimant is the stand-in, checked as in production against {@code 1.4.0}. */
    OneTimeEmbeddedEngineGrant grant() {
        return new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine(),
                Map.of("system:process-engine", PORT),
                type -> OneTimeEmbeddedEngineGrant.refusal(type, ENGINE_VERSION));
    }

    @Test
    void TR_TEN_01_the_decision_port_from_a_sealed_jar_of_the_engines_version_is_granted() throws Exception {
        // Given the stand-in in a sealed jar stamped 1.4.0, loaded by a child loader the grant's own loader cannot see,
        // as an application server or a plugin loader places the Process Engine
        try (URLClassLoader child = loader(jar("sealed-1.4.0", true, ENGINE_VERSION))) {
            Object port = newInstance(child);
            assertThatThrownBy(() -> Class.forName(PORT, false, OneTimeEmbeddedEngineGrant.class.getClassLoader()))
                    .isInstanceOf(ClassNotFoundException.class);
            OneTimeEmbeddedEngineGrant grant = grant();

            // When it claims with the child loader as the thread's context loader
            TenantScopedRuleEngine scoped = withContextLoader(child,
                    () -> grant.claim("system:process-engine", port));

            // Then it is granted, through the context loader
            assertThat(scoped.engineId()).isEqualTo("system:process-engine");
            assertThat(grant.isClaimed("system:process-engine")).isTrue();
        }
    }

    @Test
    void TR_TEN_01_a_same_named_class_in_an_unsealed_package_cannot_claim() throws Exception {
        // Given a class with the decision port's name and the engine's version, in a jar that does not seal its package
        try (URLClassLoader child = loader(jar("unsealed-1.4.0", false, ENGINE_VERSION))) {
            Object impostor = newInstance(child);
            OneTimeEmbeddedEngineGrant grant = grant();

            // When, then it is refused, and the grant stays open for the real claimant
            assertThatThrownBy(() -> withContextLoader(child, () -> grant.claim("system:process-engine", impostor)))
                    .isInstanceOf(SecurityException.class).hasMessageContaining("not in a sealed package");
            assertThat(grant.isOpen("system:process-engine")).isTrue();
        }
    }

    @Test
    void TR_TEN_01_a_same_named_class_with_a_different_version_cannot_claim() throws Exception {
        // Given a sealed jar stamped 1.3.9 against a Rule Engine at 1.4.0, and one with no version at all
        for (String version : new String[] {"1.3.9", null}) {
            try (URLClassLoader child = loader(jar("sealed-" + version, true, version))) {
                Object impostor = newInstance(child);
                OneTimeEmbeddedEngineGrant grant = grant();

                // When, then
                assertThatThrownBy(() -> withContextLoader(child,
                        () -> grant.claim("system:process-engine", impostor)))
                        .as(version).isInstanceOf(SecurityException.class)
                        .hasMessageContaining("Implementation-Version " + version);
                assertThat(grant.isOpen("system:process-engine")).isTrue();
            }
        }
    }

    @Test
    void TR_TEN_01_a_class_that_is_not_the_one_the_name_resolves_to_cannot_claim() throws Exception {
        // Given two sealed, correctly stamped copies in two loaders; the thread's context loader resolves the first
        try (URLClassLoader resolved = loader(jar("first", true, ENGINE_VERSION));
                URLClassLoader other = loader(jar("second", true, ENGINE_VERSION))) {
            Object copy = newInstance(other);
            OneTimeEmbeddedEngineGrant grant = grant();

            // When the second copy claims, then it is refused: not the expected decision-port class
            assertThatThrownBy(() -> withContextLoader(resolved, () -> grant.claim("system:process-engine", copy)))
                    .isInstanceOf(SecurityException.class).hasMessageContaining("Only " + PORT);
        }
    }

    @Test
    void TR_TEN_01_without_a_context_loader_the_grants_own_loader_is_used() {
        // Given no context loader, and a name only the grant's own loader resolves
        OneTimeEmbeddedEngineGrant grant = new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine(),
                Map.of("system:process-engine", Claimant.class.getName()),
                type -> OneTimeEmbeddedEngineGrant.refusal(type, ENGINE_VERSION));

        // When it claims, then the class resolves, and the class directory it comes from is refused for not sealing
        assertThatThrownBy(() -> withContextLoader(null, () -> grant.claim("system:process-engine", new Claimant())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not in a sealed package");
    }

    @Test
    void TR_TEN_01_a_rule_engine_without_a_version_grants_nothing() throws Exception {
        try (URLClassLoader child = loader(jar("no-engine-version", true, ENGINE_VERSION))) {
            assertThat(OneTimeEmbeddedEngineGrant.refusal(child.loadClass(PORT), null))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("no Implementation-Version"));
        }
    }

    @Test
    void TR_TEN_01_the_wiring_learns_why_the_registered_claimant_would_be_refused() {
        // In this module the Process Engine is absent
        assertThat(EmbeddedEngineGrants.registeredClaimantRefusal("system:process-engine"))
                .hasValueSatisfying(reason -> assertThat(reason).endsWith("is not on the class path"));
        assertThat(EmbeddedEngineGrants.registeredClaimantRefusal("system:timer")).isPresent();
    }

    static final class Claimant {
    }

    private static Path jar(String name, boolean sealed, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        if (sealed) {
            Attributes section = new Attributes();
            section.put(Attributes.Name.SEALED, "true");
            manifest.getEntries().put("io/github/dhluong90/bpm/fixture/decisionport/", section);
        }
        Path jar = dir.resolve(name + ".jar");
        String entry = PORT.replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new JarEntry(entry));
            jarOut.write(Files.readAllBytes(classes.resolve(entry)));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static URLClassLoader loader(Path jar) throws IOException {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Object newInstance(ClassLoader loader) throws ReflectiveOperationException {
        return Class.forName(PORT, false, loader).getConstructor().newInstance();
    }

    private static <T> T withContextLoader(ClassLoader loader, java.util.function.Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}

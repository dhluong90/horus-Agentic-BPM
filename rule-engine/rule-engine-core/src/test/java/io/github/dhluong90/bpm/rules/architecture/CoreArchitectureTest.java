package io.github.dhluong90.bpm.rules.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boundary rules of rule-engine-core: the JDK and Jackson 3 (internal only), no framework, no I/O, no
 * code execution paths.
 * Dependency-level bans are also enforced by the Maven Enforcer rule in this module's POM, which
 * catches dependencies ArchUnit cannot see because no class references them.
 */
class CoreArchitectureTest {

    /**
     * HTTP and model client packages, one list for every engine module:
     * tools/architecture/http-and-model-clients.txt at the repository root (bpm.root, passed by Surefire).
     */
    static final String[] HTTP_AND_MODEL_CLIENTS = httpAndModelClients();

    private static String[] httpAndModelClients() {
        Path list = Path.of(System.getProperty("bpm.root", "../.."),
                "tools", "architecture", "http-and-model-clients.txt");
        try {
            return Files.readAllLines(list).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + list.toAbsolutePath(), e);
        }
    }

    private static JavaClasses core;

    @BeforeAll
    static void importCore() {
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                // Both package roots while the ADR-0022 rename is pending: new code is under io.github.dhluong90.bpm.
                .importPackages("io.github.dhluong90.bpm.rules", "io.github.dhluong90.bpm.rules");
    }

    @Test
    void RLE_021_the_reference_feel_engine_is_reachable_from_tests_only() {
        // The reference FEEL implementation is the oracle of the differential test; it never ships. The enforcer
        // rule of the root POM refuses it, and Scala, in compile, runtime and provided scope in every module.
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.camunda.feel..", "scala..", "fastparse..")
                .because("the reference FEEL implementation is test scope only (RLE-021, TR-STD-02)")
                .check(core);
    }

    @Test
    void TR_EMB_03_core_has_no_framework_or_sql_imports() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "javax.sql..")
                .because("rule-engine-core is framework-free (TR-EMB-03)")
                .check(core);
    }

    @Test
    void TR_EMB_05_core_has_no_network_or_file_io() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("java.net..", "java.nio.file..", "java.nio.channels..")
                .orShould().dependOnClassesThat().belongToAnyOf(
                        File.class, FileInputStream.class, FileOutputStream.class,
                        FileReader.class, FileWriter.class, RandomAccessFile.class)
                .because("engine code performs no network or file I/O (TR-EMB-05)")
                .check(core);
    }

    @Test
    void TR_SEC_01_core_has_no_scripting_reflection_class_loading_or_deserialisation() {
        // One exception: the grant of the tenant-scoped entry point resolves one constant class name, uninitialised,
        // to compare the claimant's class identity (#336). The rule below keeps it the only one.
        noClasses()
                .that().doNotHaveFullyQualifiedName(GRANT)
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "javax.script..",
                        "org.springframework.expression..",
                        "java.lang.reflect..",
                        "java.lang.invoke..")
                .orShould().dependOnClassesThat().belongToAnyOf(
                        ObjectInputStream.class, ProcessBuilder.class, Runtime.class)
                .orShould().dependOnClassesThat().areAssignableTo(ClassLoader.class)
                .orShould().callMethodWhere(target(name("forName")).and(target(owner(assignableTo(Class.class)))))
                .orShould().callMethodWhere(target(name("newInstance")).and(target(owner(assignableTo(Class.class)))))
                .because("rule definitions never execute arbitrary code (TR-SEC-01, ADR-0003)")
                .check(core);
    }

    @Test
    void TR_API_06_jackson_3_only_inside_internal_and_never_jackson_2_databind() {
        noClasses()
                .that().resideInAnyPackage("io.github.dhluong90.bpm.rules.api..", "io.github.dhluong90.bpm.rules.spi..",
                        "io.github.dhluong90.bpm.rules.api..", "io.github.dhluong90.bpm.rules.spi..")
                .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
                .because("no JSON library type appears in the public API (ADR-0016, ADR-0021)")
                .check(core);
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson.databind..")
                .because("the core uses Jackson 3 only (ADR-0021)")
                .check(core);
    }

    @Test
    void TR_EMB_03_public_api_does_not_depend_on_internal() {
        noClasses()
                .that().resideInAnyPackage("io.github.dhluong90.bpm.rules.api..", "io.github.dhluong90.bpm.rules.spi..",
                        "io.github.dhluong90.bpm.rules.api..", "io.github.dhluong90.bpm.rules.spi..")
                .should().dependOnClassesThat().resideInAnyPackage("io.github.dhluong90.bpm.rules.internal..",
                        "io.github.dhluong90.bpm.rules.internal..")
                .because("internal has no compatibility promise (ADR-0016)")
                .check(core);
    }

    @Test
    void TR_TEN_01_the_engine_to_engine_entry_point_stays_out_of_the_host_api() {
        noClasses()
                .that().resideInAnyPackage("io.github.dhluong90.bpm.rules.api..", "io.github.dhluong90.bpm.rules.spi..")
                .should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.rules.embedded..")
                .because("the host-facing API never leads to the tenant-scoped entry point (ADR-0027)")
                .check(core);
        noClasses()
                .that().resideInAPackage("io.github.dhluong90.bpm.rules.embedded..")
                .should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.rules.internal..")
                .because("the engine-to-engine package uses only JDK and rules.api types (ADR-0016)")
                .check(core);
        noClasses()
                .that().resideOutsideOfPackages("io.github.dhluong90.bpm.rules.embedded..",
                        "io.github.dhluong90.bpm.rules.internal.service..")
                .should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.rules.embedded..")
                .because("only the one-time grant builds the tenant-scoped entry point (ADR-0027)")
                .check(core);
    }

    private static final String GRANT = "io.github.dhluong90.bpm.rules.internal.service.OneTimeEmbeddedEngineGrant";

    @Test
    void TR_SEC_01_the_grant_loads_no_class_and_uses_no_reflection_beyond_one_identity_lookup() {
        noClasses()
                .that().haveFullyQualifiedName(GRANT)
                .should().dependOnClassesThat().resideInAnyPackage("javax.script..", "java.lang.reflect..",
                        "java.lang.invoke..")
                .orShould().callMethodWhere(target(name("newInstance")))
                .orShould().callMethodWhere(target(name("loadClass")))
                .because("the claimant check compares class identity and creates nothing (#336)")
                .check(core);
    }

    @Test
    void TR_TEN_01_only_the_one_time_grant_builds_the_tenant_scoped_entry_point() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants")
                .and().doNotHaveFullyQualifiedName(GRANT)
                .should().callConstructorWhere(target(owner(name(GRANT))))
                .because("only the engine wiring opens a grant, never RuleEngines.create (#336)")
                .check(core);
        noClasses()
                .that().doNotHaveFullyQualifiedName(GRANT)
                .should().callConstructorWhere(target(owner(name(
                        "io.github.dhluong90.bpm.rules.internal.service.EngineScopedRuleEngine"))))
                .because("the entry point is handed out once, to the registered claimant (#336)")
                .check(core);
        classes()
                .that().implement("io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine")
                .or().implement("io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant")
                .should().notBePublic()
                .because("host code cannot build the entry point or a grant of its own (#336)")
                .check(core);
        noClasses()
                .that().resideInAPackage("io.github.dhluong90.bpm.rules.internal.service..")
                .should().callMethod(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.class, "claim",
                        String.class, Object.class)
                .orShould().callMethod("io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants", "open",
                        "io.github.dhluong90.bpm.rules.internal.service.RuleEngines")
                .because("the Rule Engine hands the entry point out and never opens or claims a grant itself (#336)")
                .check(core);
    }

    @Test
    void TR_AGT_03_core_uses_no_http_or_model_client_library() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(HTTP_AND_MODEL_CLIENTS)
                .because("the engine never embeds or calls a model, and adapters live in the host (TR-AGT-03, TR-EMB-05)")
                .check(core);
    }

    @Test
    void TR_EMB_07_core_uses_no_jackson_2_databind() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
                .because("the cores parse with Jackson 3 only, one JSON major on every Spring Boot line (ADR-0021)")
                .check(core);
    }

    @Test
    void TR_EMB_03_core_depends_only_on_the_jdk_jackson_3_and_itself() {
        // Allowlist: a new library (a model SDK, a second JSON library) is refused without updating any list.
        classes()
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "tools.jackson..", "com.fasterxml.jackson.annotation..", "io.github.dhluong90.bpm.rules..",
                        "io.github.dhluong90.bpm.rules..",
                        // Money, the value mapping and the horus-expr-v1 parser and evaluator (ADR-0027).
                        "io.github.dhluong90.bpm.expr..",
                        // The YAML parser of jackson-dataformat-yaml, read directly only to refuse anchors, aliases
                        // and tags that Jackson does not report (security review of #1, finding 7).
                        "org.snakeyaml.engine..",
                        // The JDK streaming XML parser for DMN XML, hardened in one place (no DTD, no external entity).
                        "javax.xml", "javax.xml.stream..", "javax.xml.namespace..",
                        // JDK cryptography (HMAC-SHA256), used to sign list cursors (RLE-039, QA review of #147).
                        "javax.crypto..")
                .because("rule-engine-core depends on the JDK, Jackson 3 and bpm-expression only (TR-EMB-03, ADR-0021, "
                        + "ADR-0027)")
                .check(core);
    }

    @Test
    void TR_SEC_01_feel_and_dmn_packages_use_no_reflection_scripting_or_network() {
        noClasses()
                .that().resideInAnyPackage("io.github.dhluong90.bpm.rules.internal.feel..",
                        "io.github.dhluong90.bpm.rules.internal.dmn..")
                .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "javax.script..", "java.net..")
                .because("FEEL and DMN evaluate untrusted models: no reflection, scripting or network (TR-SEC-01, ADR-0019)")
                .check(core);
    }
}

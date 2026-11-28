package io.github.dhluong90.bpm.expr.architecture;

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
import java.io.ObjectInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boundary rules of bpm-expression, equal to those of rule-engine-core (ADR-0027): the JDK only, no framework, no
 * I/O, no code execution path, and a public API that never leads to the internals.
 */
class ExpressionArchitectureTest {

    private static JavaClasses library;

    @BeforeAll
    static void importLibrary() {
        library = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.expr");
    }

    @Test
    void TR_EMB_03_expression_library_depends_on_the_jdk_only() {
        classes().should().onlyDependOnClassesThat().resideInAnyPackage("java..", "io.github.dhluong90.bpm.expr..")
                .because("bpm-expression has no storage, no Spring and no JSON library (ADR-0027)")
                .check(library);
    }

    @Test
    void TR_EMB_05_expression_library_has_no_network_or_file_io() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("java.net..", "java.nio.file..", "java.nio.channels..", "java.sql..")
                .orShould().dependOnClassesThat().belongToAnyOf(File.class, FileInputStream.class,
                        FileOutputStream.class)
                .because("expressions perform no I/O (TR-EMB-05)")
                .check(library);
    }

    @Test
    void TR_SEC_01_expression_library_has_no_scripting_reflection_class_loading_or_deserialisation() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("javax.script..", "java.lang.reflect..", "java.lang.invoke..")
                .orShould().dependOnClassesThat().belongToAnyOf(ObjectInputStream.class, ProcessBuilder.class,
                        Runtime.class)
                .orShould().dependOnClassesThat().areAssignableTo(ClassLoader.class)
                .orShould().callMethodWhere(target(name("forName")).and(target(owner(assignableTo(Class.class)))))
                .because("an expression never executes arbitrary code (TR-SEC-01, ADR-0003)")
                .check(library);
    }

    @Test
    void TR_EMB_03_api_and_spi_do_not_depend_on_internal() {
        noClasses().that().resideInAnyPackage("io.github.dhluong90.bpm.expr.api..", "io.github.dhluong90.bpm.expr.spi..")
                .should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.expr.internal..")
                .because("internal has no compatibility promise (ADR-0016)")
                .check(library);
    }

    @Test
    void TR_DET_05_no_expression_reads_the_system_clock() {
        noClasses().should().callMethodWhere(target(name("now")).and(target(owner(assignableTo(java.time.temporal.Temporal.class)))))
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(java.time.Clock.class, "systemUTC")
                .orShould().callMethod(java.time.Clock.class, "systemDefaultZone")
                .because("today() and now() read the evaluation context's clock only (TR-DET-05)")
                .check(library);
    }
    @Test
    void ADR_0028_feel_reads_no_default_locale_or_zone() {
        noClasses().that().resideInAPackage("io.github.dhluong90.bpm.expr.feel..")
                .should().callMethod(java.util.Locale.class, "getDefault")
                .orShould().callMethod(java.time.ZoneId.class, "systemDefault")
                .orShould().callMethod(java.util.TimeZone.class, "getDefault")
                .orShould().callMethod(String.class, "toUpperCase")
                .orShould().callMethod(String.class, "toLowerCase")
                .orShould().callMethod(String.class, "format", String.class, Object[].class)
                .because("a result may not depend on the host's locale or zone (ADR-0028 determinism rules)")
                .check(library);
    }

    @Test
    void ADR_0028_feel_contract_does_not_leak_into_api_and_spi() {
        noClasses().that().resideInAnyPackage("io.github.dhluong90.bpm.expr.api..", "io.github.dhluong90.bpm.expr.spi..")
                .should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.expr.feel..")
                .because("the feel package has no 1.x compatibility promise until the 1.2 gate (ADR-0028)")
                .check(library);
    }
}

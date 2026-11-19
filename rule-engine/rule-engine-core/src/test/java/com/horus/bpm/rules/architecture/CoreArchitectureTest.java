package com.horus.bpm.rules.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boundary rules of rule-engine-core: the JDK and Jackson 3 (internal only), no framework, no I/O, no
 * code execution paths.
 * Dependency-level bans are also enforced by the Maven Enforcer rule in this module's POM, which
 * catches dependencies ArchUnit cannot see because no class references them.
 */
class CoreArchitectureTest {

    private static JavaClasses core;

    @BeforeAll
    static void importCore() {
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.horus.bpm.rules");
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
        noClasses()
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
                .that().resideInAnyPackage("com.horus.bpm.rules.api..", "com.horus.bpm.rules.spi..")
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
                .that().resideInAnyPackage("com.horus.bpm.rules.api..", "com.horus.bpm.rules.spi..")
                .should().dependOnClassesThat().resideInAnyPackage("com.horus.bpm.rules.internal..")
                .because("internal has no compatibility promise (ADR-0016)")
                .check(core);
    }
}

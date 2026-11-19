package com.horus.bpm.rules.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Boundary rules of rule-engine-core: the JDK only, no framework, no I/O, no scripting. */
class CoreArchitectureTest {

    private static JavaClasses core;

    @BeforeAll
    static void importCore() {
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.horus.bpm.rules");
    }

    @Test
    void tr_emb_03_core_has_no_spring_sql_or_network_imports() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..", "java.net..")
                .because("rule-engine-core is framework-free and performs no I/O (TR-EMB-03, TR-EMB-05)")
                .check(core);
    }

    @Test
    void tr_sec_01_core_has_no_scripting_or_reflective_invoke() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("javax.script..", "org.springframework.expression..")
                .orShould().callMethod(java.lang.reflect.Method.class, "invoke", Object.class, Object[].class)
                .because("rule definitions never execute arbitrary code (TR-SEC-01, ADR-0003)")
                .check(core);
    }
}

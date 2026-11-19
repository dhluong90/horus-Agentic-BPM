package io.github.dhluong90.bpm.process.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The kernel rule of the contract: the model and the kernel perform no I/O and use no reflection (TR-EMB-03, TR-SEC-01). */
class KernelContractArchitectureTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.dhluong90.bpm.process");

    @Test
    void kernel_and_model_do_no_io_and_no_reflection() {
        noClasses().that().resideInAnyPackage("..internal.exec..", "..internal.model..", "..internal.expr..",
                        "..internal.json..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "java.net..",
                        "java.net.http..", "java.io..", "java.nio.file..", "java.lang.reflect..", "javax.script..",
                        "org.springframework..")
                .check(CORE);
    }

    @Test
    void core_loads_no_classes_by_name_and_starts_no_processes() {
        noClasses().should(com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere(
                        com.tngtech.archunit.base.DescribedPredicate.describe("Class.forName",
                                call -> call.getTargetOwner().isEquivalentTo(Class.class)
                                        && call.getName().equals("forName"))))
                .orShould().dependOnClassesThat().belongToAnyOf(ClassLoader.class, Runtime.class, ProcessBuilder.class)
                .check(CORE);
    }

    @Test
    void core_does_not_depend_on_frameworks() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                "java.sql..", "com.fasterxml.jackson.databind..")
                .check(CORE);
    }
}

package io.github.dhluong90.bpm.examples.ruleandprocess;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The sample host uses only the engines' public packages, as any host should (ADR-0016). */
class HostImportsTest {

    @Test
    void TR_SEC_08_the_host_imports_the_problem_handlers_from_the_public_package_only() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.github.dhluong90.bpm.host.autoconfigure..", "io.github.dhluong90.bpm..internal..")
                .because("those packages carry no compatibility promise; the handlers are in host.security")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.github.dhluong90.bpm.examples.ruleandprocess"));
    }
}

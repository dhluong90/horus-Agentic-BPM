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
    void TR_ASY_01_only_the_async_executor_calls_an_adapter_and_it_opens_no_transaction_around_the_call() {
        // The one caller of ServiceAdapter.execute is AsyncStepExecutor, which reaches the database only through
        // TransactionRunner (claim, result) and refuses to call while a transaction is active (ADR-0008).
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor")
                .should(com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere(
                        com.tngtech.archunit.base.DescribedPredicate.describe("ServiceAdapter.execute",
                                call -> call.getTargetOwner().isAssignableTo(
                                        io.github.dhluong90.bpm.process.spi.ServiceAdapter.class)
                                        && call.getName().equals("execute"))))
                .check(CORE);
        noClasses().that().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.store.ProcessStore")
                .check(CORE);
    }

    @Test
    void core_does_not_depend_on_frameworks() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                "java.sql..", "com.fasterxml.jackson.databind..")
                .check(CORE);
    }

    @Test
    void core_does_not_depend_on_the_rule_engine() {
        noClasses().should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.rules..")
                .because("decisions go through the DecisionPort, adapted in the starter (ADR-0027)")
                .check(CORE);
    }

    @Test
    void core_reaches_expression_internals_only_through_the_language() {
        noClasses().should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "expression internals other than the horus-expr-v1 language",
                        (com.tngtech.archunit.core.domain.JavaClass c) ->
                                c.getPackageName().startsWith("io.github.dhluong90.bpm.expr.internal")
                                        && !c.getName().equals("io.github.dhluong90.bpm.expr.internal.HorusExprV1")))
                .because("gateway conditions compile through the ExpressionLanguage SPI, never parser classes "
                        + "(ADR-0027)")
                .check(CORE);
    }

    @Test
    void TR_TEN_01_only_the_engines_own_timer_path_asks_for_engine_work() {
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.ProcessServices")
                .should().callConstructorWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                                com.tngtech.archunit.core.domain.properties.HasName.Predicates.name(
                                        "io.github.dhluong90.bpm.process.internal.service.DecisionCaller$Engine"))))
                .because("engine work is built from a locked row in the fire transaction, never by a host (#336)")
                .check(CORE);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors()
                .that().areDeclaredIn("io.github.dhluong90.bpm.process.internal.service.DecisionCaller$Engine")
                .should().notBePublic().andShould().notBeProtected()
                .because("host code holding a ServiceInvoker cannot ask for engine work (#336)")
                .check(CORE);
    }

    @Test
    void TR_TEN_01_only_the_decision_port_claims_and_holds_the_tenant_scoped_entry_point() {
        noClasses().that().doNotHaveFullyQualifiedName(DECISION_PORT)
                .should().callMethod(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.class, "claim",
                        String.class, Object.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine")
                .because("the Rule Engine hands the entry point to the decision port only, once (#336)")
                .check(CORE);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that().haveFullyQualifiedName(DECISION_PORT).should().notBePublic()
                .because("host code cannot create the registered claimant (#336)")
                .check(CORE);
    }

    @Test
    void TR_TEN_01_only_the_engines_own_timer_path_asks_for_engine_work() {
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.ProcessServices")
                .should().callConstructorWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                                com.tngtech.archunit.core.domain.properties.HasName.Predicates.name(
                                        "io.github.dhluong90.bpm.process.internal.service.DecisionCaller$Engine"))))
                .because("engine work is built from a locked row in the fire transaction, never by a host (#336)")
                .check(CORE);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors()
                .that().areDeclaredIn("io.github.dhluong90.bpm.process.internal.service.DecisionCaller$Engine")
                .should().notBePublic().andShould().notBeProtected()
                .because("host code holding a ServiceInvoker cannot ask for engine work (#336)")
                .check(CORE);
    }

    private static final String DECISION_PORT = "io.github.dhluong90.bpm.process.internal.service.RuleEngineDecisionPort";
}

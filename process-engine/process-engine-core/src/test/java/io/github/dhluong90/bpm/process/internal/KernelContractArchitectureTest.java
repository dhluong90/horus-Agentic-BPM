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
        // The one caller of ServiceAdapter.execute is the remote service runner, the one caller of AgentAdapter.execute
        // is the agent runner (#52), and the one caller of a runner is
        // AsyncStepExecutor, which reaches the database only through TransactionRunner (claim, result) and refuses to
        // call while a transaction is active (ADR-0008, #363).
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.ServiceStepRunner")
                .should(com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere(
                        com.tngtech.archunit.base.DescribedPredicate.describe("ServiceAdapter.execute",
                                call -> call.getTargetOwner().isAssignableTo(
                                        io.github.dhluong90.bpm.process.spi.ServiceAdapter.class)
                                        && call.getName().equals("execute"))))
                .check(CORE);
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.AgentStepRunner")
                .should(com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere(
                        com.tngtech.archunit.base.DescribedPredicate.describe("AgentAdapter.execute",
                                call -> call.getTargetOwner().isAssignableTo(
                                        io.github.dhluong90.bpm.process.spi.AgentAdapter.class)
                                        && call.getName().equals("execute"))))
                .check(CORE);
        // The runner interface's own overload with the job payload delegates to the plain one; nothing else calls it.
        noClasses().that().doNotHaveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor")
                .and().doNotHaveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner")
                .should(com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere(
                        com.tngtech.archunit.base.DescribedPredicate.describe("AsyncStepRunner.run",
                                call -> call.getTargetOwner().isAssignableTo(
                                        io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner.class)
                                        && call.getName().equals("run"))))
                .check(CORE);
        noClasses().that().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.store.ProcessStore")
                .check(CORE);
    }

    @Test
    void PRE_058_result_guards_evaluate_in_process_only() {
        // The result check runs inside the result transaction with the instance row locked (#363): it may evaluate the
        // output schema and the guardrail rule set, never call an adapter, a runner or the network.
        noClasses().that().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AgentResultGuard")
                .or().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.ResultGuard")
                .or().haveFullyQualifiedName(
                        "io.github.dhluong90.bpm.process.internal.service.JsonSchemaSubsetValidator")
                .should().dependOnClassesThat().resideInAnyPackage("java.net..", "java.net.http..", "java.io..",
                        "java.nio.file..", "java.sql..", "javax.sql..", "java.lang.reflect..", "javax.script..")
                .orShould().dependOnClassesThat().belongToAnyOf(io.github.dhluong90.bpm.process.spi.ServiceAdapter.class,
                        io.github.dhluong90.bpm.process.spi.AdapterCall.class,
                        io.github.dhluong90.bpm.process.spi.AgentAdapter.class,
                        io.github.dhluong90.bpm.process.spi.AgentInvocation.class,
                        io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner.class,
                        io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.class,
                        Thread.class)
                .check(CORE);
    }

    @Test
    void PRE_092_offline_replay_calls_no_adapter_and_no_network() {
        // A replay re-runs the result check over recorded answers (#98): it may read storage and simulate decisions,
        // never call an adapter, a runner, the executor that calls them, or the network.
        noClasses().that().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AgentReplays")
                .or().haveFullyQualifiedName("io.github.dhluong90.bpm.process.internal.service.AgentReplayCheck")
                .should().dependOnClassesThat().resideInAnyPackage("java.net..", "java.net.http..", "java.io..",
                        "java.nio.file..", "java.lang.reflect..", "javax.script..")
                .orShould().dependOnClassesThat().belongToAnyOf(io.github.dhluong90.bpm.process.spi.ServiceAdapter.class,
                        io.github.dhluong90.bpm.process.spi.AdapterCall.class,
                        io.github.dhluong90.bpm.process.spi.AgentAdapter.class,
                        io.github.dhluong90.bpm.process.spi.AgentInvocation.class,
                        io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner.class,
                        io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.class,
                        io.github.dhluong90.bpm.process.internal.service.ServiceInvoker.class,
                        io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker.class,
                        io.github.dhluong90.bpm.process.internal.service.ProcessServices.class,
                        Thread.class)
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
    void PRE_257_core_reaches_the_expression_library_only_through_its_api_spi_and_languages() {
        noClasses().should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.expr.internal..")
                .because("conditions and scripts compile through the ExpressionLanguage SPI and the "
                        + "ExpressionLanguages factory, which also sets the script step budget; core never names a "
                        + "parser, evaluator or limits class (ADR-0027)")
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
}

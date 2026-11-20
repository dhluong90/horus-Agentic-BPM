package io.github.dhluong90.bpm.process.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The decision port is the one class of the Process Engine that claims and holds the Rule Engine's tenant-scoped entry
 * point (ADR-0027, #336), and host code can neither create it nor reach the claim.
 */
class DecisionPortArchitectureTest {

    private static final String DECISION_PORT = "io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort";

    private static JavaClasses starter;

    @BeforeAll
    static void importStarter() {
        starter = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.process");
    }

    @Test
    void TR_TEN_01_only_the_decision_port_claims_and_holds_the_tenant_scoped_entry_point() {
        noClasses().that().doNotHaveFullyQualifiedName(DECISION_PORT)
                .should().callMethod(EmbeddedEngineGrant.class, "claim", String.class, Object.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine")
                .because("the Rule Engine hands the entry point to the decision port only, once (#336)")
                .check(starter);
    }

    @Test
    void TR_TEN_01_host_code_cannot_create_the_registered_claimant() {
        classes().that().haveFullyQualifiedName(DECISION_PORT).should().notBePublic()
                .andShould().haveOnlyFinalFields()
                .because("host code cannot create the registered claimant (#336)")
                .check(starter);
        constructors().that().areDeclaredIn(DECISION_PORT).should().notBePublic().andShould().notBeProtected()
                .because("host code cannot create the registered claimant (#336)")
                .check(starter);
    }

    @Test
    void TR_TEN_01_the_registered_claimant_is_this_starters_decision_port() {
        org.assertj.core.api.Assertions.assertThat(EmbeddedEngineGrant.REGISTERED_CLAIMANTS)
                .containsExactly(java.util.Map.entry("system:process-engine", DECISION_PORT));
        org.assertj.core.api.Assertions.assertThat(RuleEngineDecisionPort.class.getName()).isEqualTo(DECISION_PORT);
    }
}

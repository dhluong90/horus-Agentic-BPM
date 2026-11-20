package io.github.dhluong90.bpm.examples.ruleandprocess;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * TR-TEN-01 on a host with both starters (#336): host code has no way to reach the Rule Engine's tenant-scoped entry
 * point. A host controller that takes {@code tenant} from a path parameter could otherwise read another tenant's rule
 * outputs and write a decision row there under the Process Engine's identity.
 */
@SpringBootTest(properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class EmbeddedEntryPointIsolationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The host's own code, a controller for example, holding what it could inject. */
    static final class HostController {
    }

    @Autowired
    ApplicationContext context;

    @Test
    void TR_TEN_01_no_bean_of_the_host_context_is_or_exposes_the_entry_point() {
        assertThat(context.getBeanNamesForType(TenantScopedRuleEngine.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RuleEngines.class)).isEmpty();
        var beans = ((org.springframework.context.ConfigurableApplicationContext) context).getBeanFactory();
        for (String name : beans.getSingletonNames()) {
            Object bean = beans.getSingleton(name);
            if (bean == null) {
                continue;
            }
            assertThat(bean).as(name).isNotInstanceOf(TenantScopedRuleEngine.class)
                    .isNotInstanceOf(RuleEngines.class);
        }
        assertThat(context.getBean(RuleEngine.class)).isNotInstanceOf(TenantScopedRuleEngine.class);
    }

    @Test
    void TR_TEN_01_the_process_engine_claimed_the_entry_point_at_start_up_and_a_host_is_refused() {
        // Given the grant, which host code can inject
        EmbeddedEngineGrant grant = context.getBean(EmbeddedEngineGrant.class);

        // Then the Process Engine already holds the entry point
        assertThat(grant.isClaimed("system:process-engine")).isTrue();

        // And a host that claims it directly is refused for not being the registered claimant
        assertThatThrownBy(() -> grant.claim("system:process-engine", new HostController()))
                .isInstanceOf(SecurityException.class);

        // And the registered claimant is the process starter's decision port, which host code cannot create: the class
        // and its constructor are package-private (ADR-0027)
        assertThat(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.REGISTERED_CLAIMANTS)
                .containsEntry("system:process-engine", "io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort");
        Class<?> claimant = assertDoesNotThrowClass("io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort");
        assertThat(Modifier.isPublic(claimant.getModifiers())).isFalse();
        assertThat(Arrays.stream(claimant.getDeclaredConstructors())
                .noneMatch(c -> Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())))
                .isTrue();
    }

    @Test
    void TR_TEN_01_a_host_class_in_the_engines_package_is_refused_by_the_jvm() {
        // The engine packages are sealed in the engine jars. When the engine runs from class directories instead (an
        // IDE, or Maven goals run without the package phase), there is no jar to seal, so there is nothing to check.
        assumeTrue(DecisionCaller.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"),
                "the Process Engine runs from its jar");
        assertThat(DecisionCaller.class.getPackage().isSealed()).isTrue();
        assertThat(RuleEngines.class.getPackage().isSealed()).isTrue();
        assertThat(TenantScopedRuleEngine.class.getPackage().isSealed()).isTrue();
        Class<?> claimant = assertDoesNotThrowClass("io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort");
        assertThat(claimant.getPackage().isSealed()).as("the decision port's package in the process starter jar").isTrue();

        // When host code in process.internal.service, which would build the decision port over a grant of its own
        // and ask for engine work in bank-b, is loaded
        assertThatThrownBy(() -> Class.forName(
                "io.github.dhluong90.bpm.process.internal.service.SplitPackageAttacker"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("sealing violation");

        // And host code beside the decision port in the starter's package, which would build the registered claimant
        // over a grant of its own and evaluate in bank-b, is refused too
        assertThatThrownBy(() -> Class.forName(
                "io.github.dhluong90.bpm.process.autoconfigure.ClaimantSplitPackageAttacker"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("sealing violation");
    }

    private static Class<?> assertDoesNotThrowClass(String name) {
        try {
            return Class.forName(name, false, EmbeddedEntryPointIsolationIT.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(name + " is not on the class path", e);
        }
    }

    @Test
    void TR_TEN_01_a_grant_over_a_rule_engine_the_host_built_is_useless_to_the_host() {
        // Given a Rule Engine the host built itself over the same database, and a grant opened over it
        RuleEngines own = io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines.create(
                context.getBean(javax.sql.DataSource.class),
                context.getBean(io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions.class),
                () -> "bank-b", context.getBean(io.github.dhluong90.bpm.rules.spi.CallerResolver.class),
                java.time.Clock.systemUTC(), io.github.dhluong90.bpm.rules.internal.service.EngineSettings.defaults());
        EmbeddedEngineGrant grant = own.openEmbeddedEngineGrant();

        // When the host claims it, then it is refused: only the process starter's sealed, package-private port can
        assertThatThrownBy(() -> grant.claim("system:process-engine", new HostController()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void TR_TEN_01_host_code_cannot_ask_for_engine_work() {
        // Only the engine's timer path builds an engine caller (defence in depth: no invoker in host hands holds the
        // decision port, which only the process starter builds)
        assertThat(Arrays.stream(DecisionCaller.Engine.class.getDeclaredConstructors())
                .noneMatch(c -> Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())))
                .isTrue();
    }

    @Test
    void TR_TEN_01_the_sample_host_imports_no_engine_internals() {
        JavaClasses host = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.examples");
        noClasses().should().dependOnClassesThat().resideInAnyPackage("io.github.dhluong90.bpm.rules.embedded..",
                        "io.github.dhluong90.bpm.rules.internal..", "io.github.dhluong90.bpm.process.internal..")
                .because("a host uses the engines' api and spi packages only; the internals and the engine-to-engine "
                        + "entry point are not for hosts (ADR-0016, ADR-0027)")
                .check(host);
    }
}

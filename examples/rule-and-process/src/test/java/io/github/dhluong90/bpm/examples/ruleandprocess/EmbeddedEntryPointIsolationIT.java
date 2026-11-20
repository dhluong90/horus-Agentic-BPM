package io.github.dhluong90.bpm.examples.ruleandprocess;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
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

        // And a host that builds the Process Engine's own invoker over the grant is refused: it was claimed once
        RuleEngine ruleEngine = context.getBean(RuleEngine.class);
        assertThatThrownBy(() -> new LocalServiceInvoker(Map.of(), Optional.of(ruleEngine), Optional.of(grant),
                Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TR_TEN_01_host_code_cannot_ask_for_engine_work() {
        // Only the engine's timer path builds an engine caller, so an invoker in host hands cannot use the entry point
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

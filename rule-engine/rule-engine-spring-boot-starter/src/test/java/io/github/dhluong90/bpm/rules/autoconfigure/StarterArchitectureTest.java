package io.github.dhluong90.bpm.rules.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import io.github.dhluong90.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boundary rules of the starter. It compiles against the Spring Boot 3.5 Actuator jar (provided scope)
 * and has no Spring Boot 4 {@code spring-boot-actuator} on its classpath, and some Actuator packages have
 * the same names on both lines. Code outside the 3.5 adapter that used them would compile against 3.5
 * and fail only at run time on a Spring Boot 4 host.
 */
class StarterArchitectureTest {

    private static final String PACKAGE = RuleEngineAutoConfiguration.class.getPackageName();
    /** By name, so this test loads no Spring Boot 3.5 type. */
    private static final String SPRING_BOOT_3_5_ADAPTER = PACKAGE + ".RuleEngineSpringBoot35HealthIndicator";
    private static final String SPRING_BOOT_3_5_CONFIGURATION =
            RuleEngineAutoConfiguration.class.getName() + "$RuleEngineSpringBoot35HealthConfiguration";
    private static final String HEALTH_CHECK = PACKAGE + ".RuleEngineHealthCheck";

    /** Spring Security types through which an engine could read the caller itself, by name. */
    private static final String[] CALLER_SOURCES = {
        "org.springframework.security.core.context.SecurityContextHolder",
        "org.springframework.security.core.context.SecurityContext",
        "org.springframework.security.core.Authentication",
        "org.springframework.security.core.GrantedAuthority"
    };

    private static JavaClasses starter;
    private static JavaClasses engine;

    @BeforeAll
    static void importStarter() {
        starter = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);
        engine = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.rules");
    }

    /**
     * Engine endpoints and services ask the host module's {@code EngineAccess} who the caller is, which
     * tenant they act in and whether they hold a permission, the same seam {@code GET /api/bpm/me} reports
     * from. An engine that resolved the tenant, read the security context or applied the role mapping
     * itself would drift from what the cockpit is shown, and could skip the tenant checks.
     */
    @Test
    void TR_SEC_03_the_engine_resolves_callers_tenants_and_permissions_only_through_engine_access() {
        noClasses()
                .should().callMethod(TenantResolver.class, "resolve", CallerContext.class)
                .orShould().callMethod(ModuleDescriptor.class, "rolePermissions")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(CALLER_SOURCES[0])
                .orShould().dependOnClassesThat().haveFullyQualifiedName(CALLER_SOURCES[1])
                .orShould().dependOnClassesThat().haveFullyQualifiedName(CALLER_SOURCES[2])
                .orShould().dependOnClassesThat().haveFullyQualifiedName(CALLER_SOURCES[3])
                .because("caller resolution, the tenant checks and permission decisions belong to"
                        + " io.github.dhluong90.bpm.host.spi.EngineAccess, so /api/bpm/me and enforcement cannot drift (ADR-0014)")
                .allowEmptyShould(false)
                .check(engine);
    }

    @Test
    void TR_EMB_02_only_the_spring_boot_3_5_adapter_uses_spring_boot_3_5_actuator_types() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(SPRING_BOOT_3_5_ADAPTER)
                .and().doNotHaveFullyQualifiedName(SPRING_BOOT_3_5_CONFIGURATION)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.boot.actuate..")
                .because("the starter compiles against the Spring Boot 3.5 Actuator jar only; elsewhere those"
                        + " types would break Spring Boot 4 hosts at run time (TR-EMB-02, ADR-0011)")
                .check(starter);
    }

    @Test
    void TR_EMB_02_the_health_check_depends_on_no_spring_type() {
        noClasses()
                .that().haveNameMatching(HEALTH_CHECK.replace(".", "\\.") + "(\\$.*)?")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("the health check is shared by both Spring Boot lines; only the adapters know a"
                        + " health API (TR-EMB-02, ADR-0011)")
                .check(starter);
    }
}

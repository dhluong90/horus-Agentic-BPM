package com.horus.bpm.rules.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    private static JavaClasses starter;

    @BeforeAll
    static void importStarter() {
        starter = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);
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

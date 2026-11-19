package io.github.dhluong90.bpm.host.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import io.github.dhluong90.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boundary rules of the host module: one place resolves the tenant and decides permissions, and the
 * contract engines and hosts compile against leaks no framework type (ADR-0016).
 */
class HostArchitectureTest {

    private static JavaClasses host;

    @BeforeAll
    static void importHost() {
        host = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.host");
    }

    @Test
    void TR_SEC_03_only_the_default_engine_access_calls_the_tenant_resolver() {
        noClasses()
                .that().doNotHaveFullyQualifiedName(DefaultEngineAccess.class.getName())
                .and().doNotHaveFullyQualifiedName(DefaultEngineAccess.class.getName() + "$Context")
                .should().callMethod(TenantResolver.class, "resolve", CallerContext.class)
                .because("the tenant checks live in EngineAccess; a second caller of the resolver would skip them")
                .check(host);
    }

    @Test
    void TR_UI_08_the_metadata_controller_reads_no_security_context_and_resolves_no_tenant() {
        noClasses()
                .that().haveSimpleName("HostMetadataController")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(TenantResolver.class.getName())
                .because("/api/bpm/me reports what EngineAccess decides, so it cannot drift from enforcement")
                .check(host);
    }

    @Test
    void the_spi_package_uses_jdk_types_and_jspecify_only() {
        classes()
                .that().resideInAPackage("io.github.dhluong90.bpm.host.spi..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.jspecify..", "io.github.dhluong90.bpm.host.spi..")
                .because("engines and hosts compile against the SPI on either Spring Boot line (ADR-0016)")
                .check(host);
    }
}

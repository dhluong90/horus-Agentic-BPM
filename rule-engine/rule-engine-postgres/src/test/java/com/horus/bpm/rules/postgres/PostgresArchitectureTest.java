package com.horus.bpm.rules.postgres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Boundary rules of rule-engine-postgres: database access only, never the network. */
class PostgresArchitectureTest {

    private static JavaClasses postgres;

    @BeforeAll
    static void importPostgres() {
        postgres = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.horus.bpm.rules.postgres");
    }

    @Test
    void TR_EMB_05_postgres_module_has_no_http_client() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "java.net..",
                        "org.springframework.web..",
                        "org.apache.hc..",
                        "okhttp3..")
                .because("engine storage never calls the network (TR-EMB-05)")
                .check(postgres);
    }

    @Test
    void TR_EMB_04_postgres_module_creates_no_connection_pool() {
        noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.zaxxer.hikari..", "org.apache.commons.dbcp2..", "org.apache.tomcat.jdbc..")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.datasource.DriverManagerDataSource")
                .because("the engine borrows the host's DataSource and never opens a pool (TR-EMB-04)")
                .check(postgres);
    }
}

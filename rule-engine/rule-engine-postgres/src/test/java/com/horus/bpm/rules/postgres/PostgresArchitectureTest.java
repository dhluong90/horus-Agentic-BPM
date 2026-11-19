package com.horus.bpm.rules.postgres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Boundary rules of rule-engine-postgres: database access only, never the network. */
class PostgresArchitectureTest {

    /**
     * HTTP and model client packages, one list for every engine module:
     * tools/architecture/http-and-model-clients.txt at the repository root (bpm.root, passed by Surefire).
     */
    static final String[] HTTP_AND_MODEL_CLIENTS = httpAndModelClients();

    private static String[] httpAndModelClients() {
        Path list = Path.of(System.getProperty("bpm.root", "../.."),
                "tools", "architecture", "http-and-model-clients.txt");
        try {
            return Files.readAllLines(list).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + list.toAbsolutePath(), e);
        }
    }

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

    @Test
    void TR_AGT_03_postgres_uses_no_http_or_model_client_library() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(HTTP_AND_MODEL_CLIENTS)
                .because("the engine never embeds or calls a model, and adapters live in the host (TR-AGT-03, TR-EMB-05)")
                .check(postgres);
    }
}

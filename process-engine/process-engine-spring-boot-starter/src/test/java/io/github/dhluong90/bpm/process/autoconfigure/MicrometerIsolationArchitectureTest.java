package io.github.dhluong90.bpm.process.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Micrometer is optional (PRE-084): a host without it must start. Only the meters class and the configuration that is
 * conditional on Micrometer may name a Micrometer type, or the meters class, so no other class fails to load with
 * {@code NoClassDefFoundError} on a host that has none.
 */
class MicrometerIsolationArchitectureTest {

    private static final String METERS = "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineMetrics";
    private static final String METERS_CONFIGURATION =
            "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineAutoConfiguration$ProcessEngineMetricsConfiguration";

    /** The tracing binding and its conditional configuration (#114) name Micrometer Tracing the same way. */
    private static final String TRACING = "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineTracing";
    private static final String TRACING_CONFIGURATION =
            "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineAutoConfiguration$ProcessEngineTracingConfiguration";

    private static final JavaClasses ENGINE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.dhluong90.bpm.process");

    @Test
    void PRE_084_only_the_meters_and_their_conditional_configuration_name_micrometer() {
        noClasses().that().doNotHaveFullyQualifiedName(METERS).and().doNotHaveFullyQualifiedName(METERS_CONFIGURATION)
                .and().doNotHaveFullyQualifiedName(TRACING).and().doNotHaveFullyQualifiedName(TRACING_CONFIGURATION)
                .and().doNotHaveSimpleName("TracedFailure")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(METERS)
                .because("a host without Micrometer must start (the engine core and the other beans load without it)")
                .check(ENGINE);
    }
}

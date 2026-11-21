package io.github.dhluong90.bpm.examples.models;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The reference adapters are never part of the engine (#100, TR-AGT-04): no engine module declares a dependency on
 * them, no engine class refers to them, and they use no vendor SDK.
 */
class EngineIndependenceTest {

    private static final Path ROOT = Path.of("../..");

    @Test
    void PRE_098_no_engine_module_depends_on_the_model_adapters() throws IOException {
        List<Path> poms;
        try (Stream<Path> files = Files.walk(ROOT, 3)) {
            poms = files.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !ROOT.relativize(p).startsWith("examples")).toList();
        }
        assertThat(poms).anySatisfy(p -> assertThat(p.toString()).contains("process-engine-core"));
        for (Path pom : poms) {
            assertThat(Files.readString(pom, StandardCharsets.UTF_8)).as(pom.toString())
                    .doesNotContain("example-model-adapters").doesNotContain("examples-parent");
        }
    }

    @Test
    void PRE_098_no_engine_class_refers_to_the_model_adapters_and_the_adapters_use_no_vendor_sdk() throws IOException {
        JavaClasses engines = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.process", "io.github.dhluong90.bpm.rules");
        assertThat(engines).isNotEmpty();
        noClasses().should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.examples..")
                .check(engines);
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).doesNotContain("com.openai").doesNotContain("com.anthropic").doesNotContain("langchain");
        JavaClasses adapters = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.examples.models");
        noClasses().should().dependOnClassesThat().resideInAnyPackage("com.openai..", "com.anthropic..")
                .check(adapters);
    }
}

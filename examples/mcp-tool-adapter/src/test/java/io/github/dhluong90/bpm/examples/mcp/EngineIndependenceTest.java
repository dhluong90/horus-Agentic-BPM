package io.github.dhluong90.bpm.examples.mcp;

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
 * Nothing from the example enters an engine artifact (#99, PRE-096, TR-AGT-04): no engine module declares a
 * dependency on it, and no engine class refers to it.
 */
class EngineIndependenceTest {

    private static final Path ROOT = Path.of("../..");

    @Test
    void PRE_096_no_engine_module_depends_on_the_example() throws IOException {
        List<Path> poms;
        try (Stream<Path> files = Files.walk(ROOT, 3)) {
            poms = files.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !ROOT.relativize(p).startsWith("examples")).toList();
        }
        assertThat(poms).anySatisfy(p -> assertThat(p.toString()).contains("process-engine-core"));
        for (Path pom : poms) {
            assertThat(Files.readString(pom, StandardCharsets.UTF_8)).as(pom.toString())
                    .doesNotContain("example-mcp-tool-adapter").doesNotContain("examples-parent");
        }
    }

    @Test
    void PRE_096_no_engine_class_refers_to_the_example() {
        JavaClasses engines = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.process", "io.github.dhluong90.bpm.rules");
        assertThat(engines).isNotEmpty();
        noClasses().should().dependOnClassesThat().resideInAPackage("io.github.dhluong90.bpm.examples..")
                .check(engines);
    }
}

package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * TR-TEN-01 in the repackaged sample host (#336): Spring Boot's launcher keeps the engine packages sealed inside the
 * nested jars. Engine classes load; a class of the host's in {@code process.internal.service}, or in the process starter's
 * {@code process.autoconfigure} beside the decision port, is refused.
 */
class SealedPackagesFatJarIT {

    @Test
    void TR_TEN_01_the_fat_jar_refuses_a_host_class_in_the_engines_sealed_package() throws Exception {
        // Given the repackaged host, and the host's own classes, the attacker among them, on loader.path
        Path jar = Path.of("target", "example-rule-and-process-" + System.getProperty("engine.version") + ".jar");
        assumeTrue(Files.isRegularFile(jar), "the repackaged jar is built in the package phase: " + jar);
        Path hostClasses = Path.of("target", "test-classes").toAbsolutePath();

        // When the probe runs inside it
        String output = run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toAbsolutePath().toString(),
                "-Dloader.path=" + hostClasses,
                "-Dloader.main=" + SealedPackageProbe.class.getName(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));

        // Then the engine loads from its sealed nested jars, and the attacker is refused
        assertThat(output).contains("ENGINE_LOADED processSealed=true rulesSealed=true starterSealed=true");
        assertThat(output).contains("\nATTACKER_REFUSED").contains("sealing violation");
        // The decision port, the registered claimant, is sealed in the process starter's jar (ADR-0027)
        assertThat(output).contains("CLAIMANT_ATTACKER_REFUSED");
        assertThat(output).doesNotContain("ATTACKER_LOADED").doesNotContain("ENGINE_FAILED")
                .doesNotContain("ATTACKER_OTHER");
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the probe did not finish in 60 s");
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}

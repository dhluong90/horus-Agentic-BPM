package io.github.dhluong90.bpm.examples.guardedagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A host where an agent assesses a loan application's documents, a rule set guards its answer, and a compliance
 * reviewer reviews every answer the guardrail refuses (#66). Both engines run inside this JVM on this database.
 */
@SpringBootApplication
public class GuardedAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuardedAgentApplication.class, args);
    }
}

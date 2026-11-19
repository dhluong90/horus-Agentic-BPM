package io.github.dhluong90.bpm.examples.ruleandprocess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A host service that embeds the Rule Engine and the Process Engine. */
@SpringBootApplication
public class RuleAndProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleAndProcessApplication.class, args);
    }
}

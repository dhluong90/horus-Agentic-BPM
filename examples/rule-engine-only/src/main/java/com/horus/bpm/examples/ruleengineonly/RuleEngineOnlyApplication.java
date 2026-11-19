package com.horus.bpm.examples.ruleengineonly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A host service that embeds the Rule Engine. It has no engine-specific code. */
@SpringBootApplication
public class RuleEngineOnlyApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineOnlyApplication.class, args);
    }
}

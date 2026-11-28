package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The demo screening agent answers from start variables; it must load under the dev profile and nowhere else (#625). */
class KycStubProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoanOriginationSteps.class);

    @Test
    void the_kyc_stub_loads_under_the_dev_profile() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context.getBeansOfType(AgentAdapter.class).values())
                        .extracting(AgentAdapter::name).containsExactly("kyc.screen"));
    }

    @Test
    void no_other_profile_loads_the_kyc_stub() {
        for (String[] profiles : new String[][] {{}, {"prod"}, {"production"}, {"prod", "cloud"}}) {
            runner.withInitializer(c -> c.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context.getBeansOfType(AgentAdapter.class))
                            .as("profiles " + String.join(",", profiles)).isEmpty());
        }
    }
}

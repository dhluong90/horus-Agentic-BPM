package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The two maker-checker settings of this release: an approval names the reviewed content (PD-139, PRE-177), and an
 * incident's resolution needs a second person (PD-126, PRE-078). Each is strict by default, and its relaxation is
 * accepted only when the dev profile is active on its own.
 */
class ReviewAndResolutionSettingsTest {

    private static final String CONTENT_HASH = "horus.bpm.processes.approval.require-content-hash";
    private static final String RESOLVE = "horus.bpm.processes.incidents.resolve-approval";

    private static ProcessEngineProperties bind(MockEnvironment environment) {
        return Binder.get(environment).bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    private static void startUp(String[] profiles, String property, String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(property, value);
        ProcessEngineAutoConfiguration.requireDevOnlyRelaxations(environment, bind(environment));
    }

    @Test
    void PRE_177_PRE_078_both_settings_are_strict_by_default_and_published_in_meta() {
        MockEnvironment defaults = new MockEnvironment();
        ProcessEngineProperties properties = bind(defaults);

        EngineSettings settings = properties.toSettings(EngineSettings.ApproverRule.MAKERS,
                EngineSettings.RetireLastVersionRule.CONFIRM);
        assertThat(settings.requireContentHash()).isTrue();
        assertThat(settings.resolveApproval()).isEqualTo(EngineSettings.ResolveApproval.FOUR_EYES);
        assertThat(new ProcessEngineAutoConfiguration().processEngineModuleDescriptor(properties, defaults).settings())
                .containsEntry("approval.require-content-hash", true)
                .containsEntry("incidents.resolve-approval", "four-eyes");
        assertThat(ProcessEngineAutoConfiguration.PERMISSIONS).contains("processes:incident:manage");
    }

    @Test
    void PRE_177_an_approval_without_the_reviewed_hash_is_accepted_only_on_the_dev_profile() {
        assertThatThrownBy(() -> startUp(new String[] {"prod"}, CONTENT_HASH, "false"))
                .hasMessageContaining(CONTENT_HASH + "=false").hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {"dev", "prod"}, CONTENT_HASH, "false"))
                .hasMessageContaining("dev profile");
        assertThatCode(() -> startUp(new String[] {"dev"}, CONTENT_HASH, "false")).doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"prod"}, CONTENT_HASH, "true")).doesNotThrowAnyException();
    }

    @Test
    void PRE_078_single_person_resolution_is_accepted_only_on_the_dev_profile_and_other_values_are_refused() {
        assertThatThrownBy(() -> startUp(new String[] {"prod"}, RESOLVE, "single"))
                .hasMessageContaining(RESOLVE + "=single").hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {}, RESOLVE, "single")).hasMessageContaining("dev profile");
        assertThatCode(() -> startUp(new String[] {"dev", "test"}, RESOLVE, "single")).doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"prod"}, RESOLVE, "four-eyes")).doesNotThrowAnyException();
        assertThatThrownBy(() -> startUp(new String[] {"dev"}, RESOLVE, "nobody"))
                .hasStackTraceContaining(RESOLVE + " must be four-eyes or single");
    }
}

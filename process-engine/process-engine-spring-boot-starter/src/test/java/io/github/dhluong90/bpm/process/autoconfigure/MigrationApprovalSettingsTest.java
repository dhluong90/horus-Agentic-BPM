package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * PRE-174 (PD-114 as amended by PD-131): a migration plan's execution is four-eyes by default; {@code single} is
 * accepted only when the {@code dev} profile is active on its own, and the dry-run bounds may be lowered only.
 */
class MigrationApprovalSettingsTest {

    private static final String APPROVAL = "horus.bpm.processes.migration.approval";

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    private static void startUp(String[] profiles, String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(APPROVAL, value);
        ProcessEngineProperties properties = Binder.get(environment)
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
        ProcessEngineAutoConfiguration.requireDevOnlyRelaxations(environment, properties);
    }

    @Test
    void PRE_174_migration_defaults_are_four_eyes_100_ids_and_one_hour() {
        MigrationSettings settings = bind(Map.of()).migration().toSettings();

        assertThat(settings.approval()).isEqualTo(MigrationPlan.Approval.FOUR_EYES);
        assertThat(settings.dryRunSampleSize()).isEqualTo(100);
        assertThat(settings.dryRunValidity()).isEqualTo(Duration.ofHours(1));
        assertThat(ProcessEngineAutoConfiguration.PERMISSIONS).contains("processes:migration:manage");
    }

    @Test
    void PRE_174_single_person_migration_is_refused_at_start_up_outside_the_dev_profile() {
        assertThatThrownBy(() -> startUp(new String[] {"prod"}, "single")).hasMessageContaining(APPROVAL + "=single")
                .hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {}, "single")).as("no profile")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> startUp(new String[] {"dev", "prod"}, "single")).as("dev beside another profile")
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> startUp(new String[] {"dev"}, "single")).doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"prod"}, "four-eyes")).doesNotThrowAnyException();
    }

    @Test
    void PRE_174_an_unknown_approval_and_raised_dry_run_bounds_are_refused_naming_the_property() {
        assertThatThrownBy(() -> bind(Map.of(APPROVAL, "none")))
                .hasStackTraceContaining("horus.bpm.processes.migration.approval must be four-eyes or single");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.migration.dry-run-sample-size", "501")))
                .rootCause().hasMessageContaining("migration.dry-run-sample-size must be 1 to 500");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.migration.dry-run-validity", "PT2H")))
                .rootCause().hasMessageContaining("migration.dry-run-validity");
        assertThat(bind(Map.of("horus.bpm.processes.migration.dry-run-sample-size", "20",
                "horus.bpm.processes.migration.dry-run-validity", "PT15M")).migration().toSettings())
                .isEqualTo(new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 20, Duration.ofMinutes(15)));
    }
}

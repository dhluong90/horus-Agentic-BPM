package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import java.time.Duration;
import java.time.Period;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * PRE-200 (#410): {@code stats.rollup-retention} and the write-lag window {@code stats.rollup-min-lag} bind with their
 * defaults and are refused when they would lose data; a shorter window is accepted on the {@code dev} profile only.
 * PRE-180 (#449): without a role catalogue the starter warns outside {@code dev}.
 */
class StatsRetentionAndRoleCatalogueSettingsTest {

    private static final String STATS = "horus.bpm.processes.stats.";

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    private static void startUp(String[] profiles, Map<String, String> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        values.forEach(environment::setProperty);
        ProcessEngineProperties properties = Binder.get(environment)
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
        ProcessEngineAutoConfiguration.requireDevOnlyRelaxations(environment, properties);
    }

    @Test
    void PRE_200_retention_defaults_to_13_months_and_the_write_lag_window_to_five_minutes() {
        NodeStatsSettings settings = bind(Map.of()).stats().toRollup();

        assertThat(settings.rollupRetention()).isEqualTo(Period.ofMonths(13));
        assertThat(settings.rollupMinLag()).isEqualTo(Duration.ofMinutes(5));
        assertThat(bind(Map.of(STATS + "rollup-retention", "P90D")).stats().toRollup().rollupRetention())
                .isEqualTo(Period.ofDays(90));
    }

    @Test
    void PRE_200_a_retention_below_the_longest_window_and_a_lag_below_the_write_lag_window_are_refused() {
        assertThatThrownBy(() -> bind(Map.of(STATS + "rollup-retention", "P7D"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("stats.rollup-retention must be at least P31D");
        assertThatThrownBy(() -> bind(Map.of(STATS + "rollup-retention", "P-1M"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of(STATS + "rollup-lag", "PT2M"))).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("stats.rollup-lag").hasMessageContaining("stats.rollup-min-lag");
        // a longer lag, and a longer window with a lag that follows it, are accepted
        assertThat(bind(Map.of(STATS + "rollup-lag", "PT10M")).stats().toRollup().rollupLag())
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(bind(Map.of(STATS + "rollup-min-lag", "PT10M", STATS + "rollup-lag", "PT10M")).stats().toRollup()
                .rollupMinLag()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void PRE_200_a_write_lag_window_below_five_minutes_is_accepted_on_the_dev_profile_only() {
        Map<String, String> shorter = Map.of(STATS + "rollup-min-lag", "PT1M", STATS + "rollup-lag", "PT1M");

        assertThatThrownBy(() -> startUp(new String[] {"prod"}, shorter)).hasMessageContaining("rollup-min-lag below")
                .hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {}, shorter)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> startUp(new String[] {"dev"}, shorter)).doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"prod"}, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void PRE_180_a_host_without_a_role_catalogue_is_warned_outside_dev_and_one_with_a_catalogue_is_not() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        RoleCatalogue hostRoles = (tenant, role) -> "BRANCH_SUPERVISOR".equals(role);

        assertThat(ProcessEngineAutoConfiguration.warnWhenRolesAreNotChecked(prod, RoleCatalogue.ACCEPT_ALL)).isTrue();
        assertThat(ProcessEngineAutoConfiguration.warnWhenRolesAreNotChecked(dev, RoleCatalogue.ACCEPT_ALL)).isFalse();
        assertThat(ProcessEngineAutoConfiguration.warnWhenRolesAreNotChecked(prod, hostRoles)).isFalse();
    }
}

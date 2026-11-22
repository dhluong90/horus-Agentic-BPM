package io.github.dhluong90.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** When the endpoints are registered, and the start-up checks on host settings. */
class HostMetadataAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class TwoRulesEntries {

        @Bean
        ModuleDescriptor first() {
            return TestHosts.rules(Map.of());
        }

        @Bean
        ModuleDescriptor second() {
            return TestHosts.rules(Map.of());
        }
    }

    @Test
    void endpoints_are_registered_when_an_engine_contributes_an_entry() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .run(context -> assertThat(context).hasSingleBean(HostMetadataController.class));
    }

    @Test
    void no_endpoints_without_an_engine_entry_so_meta_never_lists_zero_modules() {
        TestHosts.runner().run(context -> assertThat(context).doesNotHaveBean(HostMetadataController.class));
    }

    @Test
    void no_endpoints_outside_a_servlet_host() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HostMetadataAutoConfiguration.class))
                .withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .run(context -> assertThat(context).doesNotHaveBean(HostMetadataController.class));
    }

    @Test
    void host_id_longer_than_forty_characters_stops_the_start_up() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.host-id=" + "x".repeat(41))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.host-id must be 1 to 40 characters"));
    }

    @Test
    void the_removed_header_mode_stops_the_start_up_instead_of_being_ignored() {
        for (String removed : new String[] {"horus.bpm.tenancy.resolver=header:X-Tenant-Id",
                "horus.bpm.tenancy.trusted-gateway=true"}) {
            TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                    .withPropertyValues(removed, "spring.profiles.active=dev")
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("is no longer supported: the engines take the tenant only from the "
                                    + "authenticated principal (PD-079)"));
            // L3: a host that declares its own TenantResolver gets the same stop
            TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, TenantResolutionTest.FixedHostTenant.class)
                    .withPropertyValues(removed, "spring.profiles.active=dev")
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("is no longer supported: the engines take the tenant only from the "
                                    + "authenticated principal (PD-079)"));
        }
    }

    @Test
    void two_entries_for_one_module_stop_the_start_up() {
        TestHosts.runner().withUserConfiguration(TwoRulesEntries.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("Two engine entries for module 'rules'"));
    }

    @Test
    void claims_are_read_from_oauth2_principals_when_oauth2_is_present() {
        assertThat(EngineAccessAutoConfiguration.claims(getClass().getClassLoader())).isInstanceOf(OAuth2Claims.class);
        assertThat(EngineAccessAutoConfiguration.claims(new ClassLoader(null) { }))
                .isNotInstanceOf(OAuth2Claims.class);
    }
}

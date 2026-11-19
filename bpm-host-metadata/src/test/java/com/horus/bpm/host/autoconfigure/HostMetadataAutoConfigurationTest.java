package com.horus.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.host.spi.ModuleDescriptor;
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
    void unknown_tenancy_resolver_stops_the_start_up() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.resolver=query:tenant")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.tenancy.resolver must be 'security-context' or 'header:"));
    }

    @Test
    void two_entries_for_one_module_stop_the_start_up() {
        TestHosts.runner().withUserConfiguration(TwoRulesEntries.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("Two engine entries for module 'rules'"));
    }

    @Test
    void claims_are_read_from_oauth2_principals_when_oauth2_is_present() {
        assertThat(HostMetadataAutoConfiguration.claims(getClass().getClassLoader())).isInstanceOf(OAuth2Claims.class);
        assertThat(HostMetadataAutoConfiguration.claims(new ClassLoader(null) { }))
                .isNotInstanceOf(OAuth2Claims.class);
    }
}

package com.horus.bpm.host.autoconfigure;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.horus.bpm.host.spi.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Hosts used by the tests: a servlet application with Spring Security, the host metadata module, and a
 * rules entry as the Rule Engine starter contributes it. Callers are set with Spring Security's test
 * support, so no identity provider is involved.
 */
final class TestHosts {

    static final List<String> RULES_PERMISSIONS = List.of(
            "rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read");

    private TestHosts() {
    }

    static WebApplicationContextRunner runner() {
        return runner(HostSecurity.class);
    }

    static WebApplicationContextRunner runner(Class<?> security) {
        return new WebApplicationContextRunner().withUserConfiguration(security);
    }

    static MockMvc mockMvc(ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    static ModuleDescriptor rules(Map<String, List<String>> rolePermissions) {
        return new ModuleDescriptor("rules", "0.1.3", "v1", List.of(), Map.of(), RULES_PERMISSIONS, rolePermissions);
    }

    /** A host whose security chain wires the engine's problem responses for /api/bpm/**, as the wiki shows. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class HostSecurity {

        @Bean
        SecurityFilterChain hostSecurity(HttpSecurity http) {
            var bpm = PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**");
            http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(exceptions -> exceptions
                            .defaultAuthenticationEntryPointFor(new BpmProblemAuthenticationEntryPoint(), bpm)
                            .defaultAccessDeniedHandlerFor(new BpmProblemAccessDeniedHandler(), bpm));
            return http.build();
        }
    }

    /**
     * A host whose chain lets every request through to the endpoints and wires no engine handler: the
     * controller's own check is then the only control.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class PermitAllSecurity {

        @Bean
        SecurityFilterChain hostSecurity(HttpSecurity http) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
            return http.build();
        }
    }

    /** The rules entry with no role mapping: the host issues permission strings directly. */
    @Configuration(proxyBeanMethods = false)
    static class RulesWithoutMapping {

        @Bean
        ModuleDescriptor ruleEngineModuleDescriptor() {
            return rules(Map.of());
        }
    }

    /** The rules entry with the host role PRICING_APPROVER mapped to rules:approve. */
    @Configuration(proxyBeanMethods = false)
    static class RulesWithApproverMapping {

        @Bean
        ModuleDescriptor ruleEngineModuleDescriptor() {
            return rules(Map.of("ROLE_PRICING_APPROVER", List.of("rules:approve")));
        }
    }
}

package io.github.dhluong90.bpm.host.autoconfigure;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.dhluong90.bpm.host.security.BpmProblemAccessDeniedHandler;
import io.github.dhluong90.bpm.host.security.BpmProblemAuthenticationEntryPoint;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * A host whose security chain wires the engine's problem responses for /api/bpm/**, as the wiki shows: it injects
     * the registered beans, so the chain sends the challenge of {@code horus.bpm.security.challenge}.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class HostSecurity {

        @Bean
        SecurityFilterChain hostSecurity(HttpSecurity http, ObjectProvider<BpmProblemAuthenticationEntryPoint> entryPoint,
                ObjectProvider<BpmProblemAccessDeniedHandler> accessDenied) {
            var bpm = PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**");
            http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(exceptions -> exceptions
                            // Without an engine module the beans are absent; the tests of that case need a chain too
                            .defaultAuthenticationEntryPointFor(
                                    entryPoint.getIfAvailable(BpmProblemAuthenticationEntryPoint::new), bpm)
                            .defaultAccessDeniedHandlerFor(
                                    accessDenied.getIfAvailable(BpmProblemAccessDeniedHandler::new), bpm));
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

    /** Tenant settings declaring the given names; values are empty. */
    static io.github.dhluong90.bpm.host.spi.ModuleTenantSettings tenantSettings(String module, String... names) {
        return new io.github.dhluong90.bpm.host.spi.ModuleTenantSettings() {
            @Override
            public String module() {
                return module;
            }

            @Override
            public List<String> names() {
                return List.of(names);
            }

            @Override
            public Map<String, Object> settings(String tenantId) {
                return Map.of();
            }
        };
    }

    /** Tenant settings that throw for bank-a. */
    static io.github.dhluong90.bpm.host.spi.ModuleTenantSettings throwing(String name) {
        return new io.github.dhluong90.bpm.host.spi.ModuleTenantSettings() {
            @Override
            public String module() {
                return "rules";
            }

            @Override
            public List<String> names() {
                return List.of(name);
            }

            @Override
            public Map<String, Object> settings(String tenantId) {
                throw new IllegalStateException("status of " + tenantId + " is not loaded");
            }
        };
    }

    /** Tenant settings that return an amount, which /meta does not publish. */
    static io.github.dhluong90.bpm.host.spi.ModuleTenantSettings mistyped(String name) {
        return new io.github.dhluong90.bpm.host.spi.ModuleTenantSettings() {
            @Override
            public String module() {
                return "rules";
            }

            @Override
            public List<String> names() {
                return List.of(name, name + "-label");
            }

            @Override
            public Map<String, Object> settings(String tenantId) {
                return Map.of(name, new java.math.BigDecimal("50000000"), name + "-label", "VND");
            }
        };
    }

    /** The masking status beside one contributor that throws and one that returns an unsupported type. */
    @Configuration(proxyBeanMethods = false)
    static class FaultyTenantSettings {

        @Bean
        io.github.dhluong90.bpm.host.spi.ModuleTenantSettings failingStatus() {
            return throwing("limits.daily-remaining");
        }

        @Bean
        io.github.dhluong90.bpm.host.spi.ModuleTenantSettings amountStatus() {
            return mistyped("limits.daily-cap");
        }
    }

    /** A masking status like the Rule Engine's: bank-a has key a1, every other tenant none; one undeclared name. */
    @Configuration(proxyBeanMethods = false)
    static class MaskingStatus {

        @Bean
        io.github.dhluong90.bpm.host.spi.ModuleTenantSettings maskingStatus() {
            return new io.github.dhluong90.bpm.host.spi.ModuleTenantSettings() {
                @Override
                public String module() {
                    return "rules";
                }

                @Override
                public List<String> names() {
                    return List.of("masking.hmac-key", "masking.hmac-key-id");
                }

                @Override
                public Map<String, Object> settings(String tenantId) {
                    return "bank-a".equals(tenantId)
                            ? Map.of("masking.hmac-key", "set", "masking.hmac-key-id", "a1", "undeclared", "x")
                            : Map.of("masking.hmac-key", "unset", "undeclared", "x");
                }
            };
        }
    }
}

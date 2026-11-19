package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.util.ClassUtils;

/**
 * The controls every engine endpoint relies on, registered whether or not the metadata endpoints are on
 * (ADR-0024 decision 5: controls are not options): {@code Cache-Control: no-store} on engine API responses
 * (TR-SEC-09), the default {@link TenantResolver}, the default {@link EngineAccess}, the problem responses
 * for the host's security chain, and the problem answer to an {@code EngineAccess} refusal.
 * {@code horus.bpm.host-metadata.enabled} does not reach this class; it switches off only the two
 * endpoints of {@link HostMetadataAutoConfiguration}.
 *
 * <p>Active in a servlet host when at least one engine starter has contributed a {@link ModuleDescriptor}.
 * Runs after the engine auto-configurations, named as strings so that this module depends on no engine.
 * Nothing is component-scanned. Every default bean gives way to a host bean of the same type.
 */
@AutoConfiguration(afterName = {
    "io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineAutoConfiguration",
    "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
@ConditionalOnBean(ModuleDescriptor.class)
@EnableConfigurationProperties(HostMetadataProperties.class)
public class EngineAccessAutoConfiguration {

    static final String DEV_PROFILE = "dev";
    static final List<String> REMOVED_TENANCY_PROPERTIES =
            List.of("horus.bpm.tenancy.resolver", "horus.bpm.tenancy.trusted-gateway");
    private static final Log LOG = LogFactory.getLog(EngineAccessAutoConfiguration.class);

    @Bean
    FilterRegistrationBean<EngineApiCacheControlFilter> horusBpmEngineApiCacheControl() {
        FilterRegistrationBean<EngineApiCacheControlFilter> registration =
                new FilterRegistrationBean<>(new EngineApiCacheControlFilter());
        registration.addUrlPatterns(EngineApiCacheControlFilter.URL_PATTERNS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** The engine entries in order, each module once. */
    static List<ModuleDescriptor> modules(ObjectProvider<ModuleDescriptor> modules) {
        List<ModuleDescriptor> descriptors = modules.orderedStream().toList();
        Set<String> seen = new HashSet<>();
        for (ModuleDescriptor descriptor : descriptors) {
            if (!seen.add(descriptor.module())) {
                throw new IllegalStateException("Two engine entries for module '" + descriptor.module()
                        + "'. Each engine contributes exactly one ModuleDescriptor.");
            }
        }
        return descriptors;
    }

    /** Token claims when Spring Security OAuth2 is present; otherwise a principal has no claims. */
    static Function<Authentication, Map<String, Object>> claims(ClassLoader classLoader) {
        if (ClassUtils.isPresent(OAuth2Claims.CLAIM_ACCESSOR, classLoader)) {
            return new OAuth2Claims();
        }
        return authentication -> Map.of();
    }

    /** Caller, tenant and permissions, on Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.security.core.Authentication",
        "org.springframework.security.web.AuthenticationEntryPoint"
    })
    static class SecurityConfiguration {

        /**
         * The default tenant resolver, unless the host declares its own: the {@code tenancy.claim} attribute
         * of the authenticated principal, session or token (PD-079). {@code tenancy.dev-tenant} is refused
         * outside the {@code dev} profile, and the removed header mode is refused rather than ignored, so a
         * host that relied on it fails at start-up instead of silently changing behaviour. The profile check
         * matches profiles the way {@code @Profile} does, default profiles included.
         */
        @Bean
        @ConditionalOnMissingBean
        TenantResolver horusBpmTenantResolver(HostMetadataProperties properties, Environment environment) {
            for (String removed : REMOVED_TENANCY_PROPERTIES) {
                if (environment.containsProperty(removed)) {
                    throw new IllegalStateException(removed + " is no longer supported: the engines take the tenant "
                            + "only from the authenticated principal (PD-079). Remove it; for local work without an "
                            + "identity provider use horus.bpm.tenancy.dev-tenant on the dev profile.");
                }
            }
            HostMetadataProperties.Tenancy tenancy = properties.tenancy();
            if (tenancy.devTenant() != null) {
                if (!environment.matchesProfiles(DEV_PROFILE)) {
                    throw new IllegalStateException("horus.bpm.tenancy.dev-tenant fixes the tenant for local work and "
                            + "is accepted on the dev profile only. Remove it outside local development.");
                }
                LOG.warn("Principals without a " + tenancy.claim() + " attribute get the fixed development tenant '"
                        + tenancy.devTenant() + "' (horus.bpm.tenancy.dev-tenant, dev profile).");
            }
            return new PrincipalTenantResolver(tenancy.claim(), tenancy.devTenant());
        }

        /** The one caller and permission check of the engine endpoints and {@code /api/bpm/me}. */
        @Bean
        @ConditionalOnMissingBean
        EngineAccess horusBpmEngineAccess(ObjectProvider<ModuleDescriptor> modules, HostMetadataProperties properties,
                TenantResolver tenantResolver) {
            return new DefaultEngineAccess(tenantResolver, properties.tenancy().claim(),
                    claims(EngineAccessAutoConfiguration.class.getClassLoader()), properties.security().challenge(),
                    modules(modules));
        }

        /** For the host's security chain; the challenge is {@code horus.bpm.security.challenge}. */
        @Bean
        @ConditionalOnMissingBean
        BpmProblemAuthenticationEntryPoint horusBpmProblemAuthenticationEntryPoint(HostMetadataProperties properties) {
            return new BpmProblemAuthenticationEntryPoint(properties.security().challenge());
        }

        /** For the host's security chain. */
        @Bean
        @ConditionalOnMissingBean
        BpmProblemAccessDeniedHandler horusBpmProblemAccessDeniedHandler() {
            return new BpmProblemAccessDeniedHandler();
        }
    }

    /** The problem answer to an {@code EngineAccess} refusal that leaves an engine controller. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class MvcConfiguration {

        @Bean
        EngineAccessProblems horusBpmEngineAccessProblems() {
            return new EngineAccessProblems();
        }
    }
}

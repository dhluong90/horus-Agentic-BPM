package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.ModuleDescriptor;
import com.horus.bpm.host.spi.TenantResolver;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.util.ClassUtils;

/**
 * Serves {@code /api/bpm/meta} and {@code /api/bpm/me} once per host (ADR-0014), and marks engine API
 * responses {@code no-store}.
 *
 * <p>Active in a servlet host with Spring MVC and Spring Security when at least one engine starter has
 * contributed a {@link ModuleDescriptor}, unless {@code horus.bpm.host-metadata.enabled=false}. Runs after
 * the engine auto-configurations, named as strings so that this module depends on no engine. Nothing is
 * component-scanned.
 */
@AutoConfiguration(afterName = {
    "com.horus.bpm.rules.autoconfigure.RuleEngineAutoConfiguration",
    "com.horus.bpm.processes.autoconfigure.ProcessEngineAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
    "org.springframework.web.servlet.DispatcherServlet",
    "org.springframework.security.core.Authentication",
    "org.springframework.security.web.AuthenticationEntryPoint"
})
@ConditionalOnBean(ModuleDescriptor.class)
@ConditionalOnProperty(prefix = "horus.bpm.host-metadata", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HostMetadataProperties.class)
public class HostMetadataAutoConfiguration {

    static final String DEV_PROFILE = "dev";
    static final List<String> REMOVED_TENANCY_PROPERTIES =
            List.of("horus.bpm.tenancy.resolver", "horus.bpm.tenancy.trusted-gateway");
    private static final Log LOG = LogFactory.getLog(HostMetadataAutoConfiguration.class);

    /**
     * The default tenant resolver, unless the host declares its own: the {@code tenancy.claim} attribute of
     * the authenticated principal, session or token (PD-079). {@code tenancy.dev-tenant} is refused outside
     * the {@code dev} profile, and the removed header mode is refused rather than ignored, so a host that
     * relied on it fails at start-up instead of silently changing behaviour. The profile check matches
     * profiles the way {@code @Profile} does, default profiles included.
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
                throw new IllegalStateException("horus.bpm.tenancy.dev-tenant fixes the tenant for local work and is "
                        + "accepted on the dev profile only. Remove it outside local development.");
            }
            LOG.warn("Principals without a " + tenancy.claim() + " attribute get the fixed development tenant '"
                    + tenancy.devTenant() + "' (horus.bpm.tenancy.dev-tenant, dev profile).");
        }
        return new PrincipalTenantResolver(tenancy.claim(), tenancy.devTenant());
    }

    @Bean
    HostMetadataController horusBpmHostMetadataController(
            ObjectProvider<ModuleDescriptor> modules, HostMetadataProperties properties, TenantResolver tenantResolver) {
        List<ModuleDescriptor> descriptors = modules.orderedStream().toList();
        Set<String> seen = new HashSet<>();
        for (ModuleDescriptor descriptor : descriptors) {
            if (!seen.add(descriptor.module())) {
                throw new IllegalStateException("Two engine entries for module '" + descriptor.module()
                        + "'. Each engine contributes exactly one ModuleDescriptor.");
            }
        }
        CallerResolution callers = new CallerResolution(tenantResolver, properties.tenancy().claim(),
                claims(HostMetadataAutoConfiguration.class.getClassLoader()), properties.security().challenge());
        return new HostMetadataController(descriptors, properties, callers);
    }

    @Bean
    FilterRegistrationBean<EngineApiCacheControlFilter> horusBpmEngineApiCacheControl() {
        FilterRegistrationBean<EngineApiCacheControlFilter> registration =
                new FilterRegistrationBean<>(new EngineApiCacheControlFilter());
        registration.addUrlPatterns(EngineApiCacheControlFilter.URL_PATTERNS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** Token claims when Spring Security OAuth2 is present; otherwise a principal has no claims. */
    static Function<Authentication, Map<String, Object>> claims(ClassLoader classLoader) {
        if (ClassUtils.isPresent(OAuth2Claims.CLAIM_ACCESSOR, classLoader)) {
            return new OAuth2Claims();
        }
        return authentication -> Map.of();
    }
}

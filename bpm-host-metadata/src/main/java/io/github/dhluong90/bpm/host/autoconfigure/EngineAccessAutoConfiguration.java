package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.security.BpmProblemAccessDeniedHandler;
import io.github.dhluong90.bpm.host.security.BpmProblemAuthenticationEntryPoint;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.host.spi.TenantSwitchAudit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
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
    static final String TEST_PROFILE = "test";
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

    /**
     * CORS for a standalone cockpit on another origin (ADR-0014 decision 4, TR-SEC-09). Off by default: with no
     * origin in {@code horus.bpm.rest.cors.allowed-origins} the filter is registered disabled, so no CORS header is
     * ever sent and a browser refuses every cross-origin call. With origins, a call from any other origin is
     * {@code 403}, and so is its preflight; there is no wildcard and no credentials mode. The filter runs before
     * the host's security chain, so a preflight, which carries no token, is answered there. A call from the
     * host's own origin is not cross-origin and is untouched.
     *
     * <p>Outside the {@code dev} profile, an origin without a configured token audience stops the start-up
     * (TR-SEC-12): a standalone cockpit sends tokens, and a token issued for another host must be refused.
     */
    @Bean
    FilterRegistrationBean<CorsFilter> horusBpmCorsFilter(HostMetadataProperties properties, Environment environment) {
        HostMetadataProperties.Rest.Cors cors = properties.rest().cors();
        boolean on = !cors.allowedOrigins().isEmpty();
        if (on && !environment.matchesProfiles(DEV_PROFILE) && !AudiencesConfigured.in(environment)) {
            throw new IllegalStateException("horus.bpm.rest.cors.allowed-origins is set but no token audience is. "
                    + "Set horus.bpm.security.audiences (HORUS_BPM_SECURITY_AUDIENCES) to this host's audience, "
                    + "so a token issued for another host is refused (TR-SEC-12).");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(cors.allowedMethods());
        configuration.setAllowedHeaders(cors.allowedHeaders());
        configuration.setExposedHeaders(cors.exposedHeaders());
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(cors.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/bpm/**", configuration);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns(EngineApiCacheControlFilter.URL_PATTERNS);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.setEnabled(on);
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

    /**
     * The removed header mode is refused rather than ignored, so a host that relied on it fails at start-up
     * instead of silently changing behaviour. A bean of its own, so the check also runs when the host declares
     * its own {@link TenantResolver}.
     */
    @Bean
    RemovedTenancyProperties horusBpmRemovedTenancyProperties(Environment environment) {
        for (String removed : REMOVED_TENANCY_PROPERTIES) {
            if (environment.containsProperty(removed)) {
                throw new IllegalStateException(removed + " is no longer supported: the engines take the tenant "
                        + "only from the authenticated principal (PD-079). Remove it; for local work without an "
                        + "identity provider use horus.bpm.tenancy.dev-tenant on the dev profile.");
            }
        }
        return new RemovedTenancyProperties();
    }

    /** Marker of the start-up check above; it holds nothing. */
    static final class RemovedTenancyProperties {
    }

    /**
     * {@code tenancy.dev-tenant} is for a purely local start-up: the {@code dev} profile, matched the way
     * {@code @Profile} matches (default profiles included), and no profile other than {@code dev} or
     * {@code test} beside it, so a production manifest with a leftover {@code dev} ({@code prod,dev}) stops.
     */
    static void requireLocalStartUp(Environment environment) {
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            throw new IllegalStateException("horus.bpm.tenancy.dev-tenant fixes the tenant for local work and "
                    + "is accepted on the dev profile only. Remove it outside local development.");
        }
        String[] profiles = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        for (String profile : profiles) {
            if (!profile.equals(DEV_PROFILE) && !profile.equals(TEST_PROFILE)) {
                throw new IllegalStateException("horus.bpm.tenancy.dev-tenant fixes the tenant for local work and "
                        + "is accepted on the dev profile only, but profile '" + profile + "' is active too. "
                        + "Remove horus.bpm.tenancy.dev-tenant outside local development.");
            }
        }
    }

    /** Recognises token principals when Spring Security OAuth2 is present; otherwise no principal is a token. */
    static Predicate<Object> isToken(ClassLoader classLoader) {
        if (ClassUtils.isPresent(OAuth2Claims.CLAIM_ACCESSOR, classLoader)) {
            return OAuth2Claims::isToken;
        }
        return principal -> false;
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
         * of the authenticated principal, session or token (PD-079). {@code tenancy.dev-tenant} is accepted
         * on a purely local start-up only (see {@link #requireLocalStartUp}) and serves only principals that
         * are not tokens (RLE-037).
         */
        @Bean
        @ConditionalOnMissingBean
        TenantResolver horusBpmTenantResolver(HostMetadataProperties properties, Environment environment) {
            HostMetadataProperties.Tenancy tenancy = properties.tenancy();
            if (tenancy.devTenant() != null) {
                requireLocalStartUp(environment);
                LOG.warn("Principals without a " + tenancy.claim() + " attribute that are not tokens get the fixed "
                        + "development tenant '" + tenancy.devTenant() + "' (horus.bpm.tenancy.dev-tenant, dev profile).");
            }
            return new PrincipalTenantResolver(tenancy.claim(), tenancy.devTenant(), isToken(getClass().getClassLoader()));
        }

        /**
         * The audit record of an accepted tenant switch (#451), unless the host declares its own sink: one line on
         * the {@code io.github.dhluong90.bpm.audit} logger.
         */
        @Bean
        @ConditionalOnMissingBean
        TenantSwitchAudit horusBpmTenantSwitchAudit() {
            return new LoggingTenantSwitchAudit();
        }

        /** The one caller and permission check of the engine endpoints and {@code /api/bpm/me}. */
        @Bean
        @ConditionalOnMissingBean
        EngineAccess horusBpmEngineAccess(ObjectProvider<ModuleDescriptor> modules, HostMetadataProperties properties,
                TenantResolver tenantResolver, TenantSwitchAudit tenantSwitchAudit) {
            return new DefaultEngineAccess(tenantResolver, properties.tenancy(),
                    claims(EngineAccessAutoConfiguration.class.getClassLoader()), properties.security(),
                    modules(modules), tenantSwitchAudit, java.time.Clock.systemUTC());
        }

        /**
         * Readable names of roles, groups and users (#442), unless the host declares its own: the
         * {@code horus.bpm.display-names} settings.
         */
        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.host.spi.DisplayNames horusBpmDisplayNames(HostMetadataProperties properties) {
            return new PropertyDisplayNames(properties.displayNames());
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

    /**
     * Audience validation of bearer tokens (TR-SEC-12): when {@code horus.bpm.security.audiences} is set, a token
     * whose {@code aud} claim names none of them is rejected with {@code 401}. Spring Boot's JWT decoder applies
     * this validator beside its issuer check. A host that builds its own {@code JwtDecoder} adds it from the
     * {@code OAuth2TokenValidator<Jwt>} beans itself; outside {@code dev}, with an audience configured, every
     * {@code JwtDecoder} the host defines itself is named in one start-up WARN that says the starter applies no
     * audience check to it (#451, {@link HostJwtDecoderAudienceWarning}).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.Jwt")
    static class AudienceConfiguration {

        @Bean
        @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
        static HostJwtDecoderAudienceWarning horusBpmHostJwtDecoderAudienceWarning(Environment environment) {
            return new HostJwtDecoderAudienceWarning(environment);
        }

        @Bean
        @Conditional(StarterAudiencesCondition.class)
        OAuth2TokenValidator<Jwt> horusBpmAudienceValidator(HostMetadataProperties properties) {
            List<String> accepted = properties.security().audiences();
            return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.stream().anyMatch(accepted::contains));
        }
    }

    /** True when {@code horus.bpm.security.audiences} lists at least one audience. */
    static final class StarterAudiencesCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment())
                    .bind(AudiencesConfigured.STARTER_PROPERTY, Bindable.listOf(String.class))
                    .map(values -> values.stream().anyMatch(value -> !value.isBlank())).orElse(false);
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

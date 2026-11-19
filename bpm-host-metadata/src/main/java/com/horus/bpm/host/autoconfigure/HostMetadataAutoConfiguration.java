package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.ModuleDescriptor;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.util.ClassUtils;

/**
 * Serves {@code /api/bpm/meta} and {@code /api/bpm/me} once per host (ADR-0014).
 *
 * <p>Active in a servlet host with Spring MVC and Spring Security when at least one engine starter has
 * contributed a {@link ModuleDescriptor}. Runs after the engine auto-configurations, named as strings so
 * that this module depends on no engine. Nothing is component-scanned.
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
@EnableConfigurationProperties(HostMetadataProperties.class)
public class HostMetadataAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(HostMetadataAutoConfiguration.class);

    @Bean
    HostMetadataController horusBpmHostMetadataController(
            ObjectProvider<ModuleDescriptor> modules, HostMetadataProperties properties, Environment environment) {
        List<ModuleDescriptor> descriptors = modules.orderedStream().toList();
        Set<String> seen = new HashSet<>();
        for (ModuleDescriptor descriptor : descriptors) {
            if (!seen.add(descriptor.module())) {
                throw new IllegalStateException("Two engine entries for module '" + descriptor.module()
                        + "'. Each engine contributes exactly one ModuleDescriptor.");
            }
        }
        String header = properties.tenancy().tenantHeader();
        if (header != null && !Set.of(environment.getActiveProfiles()).contains("dev")) {
            LOG.warn("horus.bpm.tenancy.resolver takes the tenant from the " + header + " header. Use this only "
                    + "behind a gateway that authenticates the caller and sets the header itself.");
        }
        return new HostMetadataController(descriptors, properties,
                new CallerResolution(properties.tenancy(), claims(HostMetadataAutoConfiguration.class.getClassLoader())));
    }

    /** Token claims when Spring Security OAuth2 is present; otherwise a principal has no claims. */
    static Function<Authentication, Map<String, Object>> claims(ClassLoader classLoader) {
        if (ClassUtils.isPresent(OAuth2Claims.CLAIM_ACCESSOR, classLoader)) {
            return new OAuth2Claims();
        }
        return authentication -> Map.of();
    }
}

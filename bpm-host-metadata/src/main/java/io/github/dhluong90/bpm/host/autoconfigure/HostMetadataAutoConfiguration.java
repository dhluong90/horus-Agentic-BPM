package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Serves {@code /api/bpm/meta} and {@code /api/bpm/me} once per host (ADR-0014).
 *
 * <p>Active in a servlet host with Spring MVC and Spring Security when at least one engine starter has
 * contributed a {@link ModuleDescriptor}, unless {@code horus.bpm.host-metadata.enabled=false}. That switch
 * turns off these two endpoints only: the security controls of {@link EngineAccessAutoConfiguration} stay
 * on. The caller, tenant and permissions come from the host's {@link EngineAccess}, the same bean the
 * engine endpoints use. Nothing is component-scanned.
 */
@AutoConfiguration(after = EngineAccessAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
    "org.springframework.web.servlet.DispatcherServlet",
    "org.springframework.security.core.Authentication",
    "org.springframework.security.web.AuthenticationEntryPoint"
})
@ConditionalOnBean({ModuleDescriptor.class, EngineAccess.class})
@ConditionalOnProperty(prefix = "horus.bpm.host-metadata", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HostMetadataProperties.class)
public class HostMetadataAutoConfiguration {

    @Bean
    HostMetadataController horusBpmHostMetadataController(
            ObjectProvider<ModuleDescriptor> modules, HostMetadataProperties properties, EngineAccess access) {
        return new HostMetadataController(EngineAccessAutoConfiguration.modules(modules), properties, access);
    }
}

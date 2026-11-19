package com.horus.bpm.cockpit.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Serves the cockpit inside a Spring MVC host (embedded mode, ADR-0005 and ADR-0014).
 *
 * <p>The cockpit is static files with no state; the host's own authentication protects it and its
 * REST layer is the only thing it calls. Registered through {@code AutoConfiguration.imports}; nothing
 * is component-scanned. Uses Spring MVC only, so it runs on both supported Spring Boot lines (ADR-0011).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "horus.bpm.cockpit", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CockpitProperties.class)
public class CockpitAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(CockpitAutoConfiguration.class);

    @Bean
    CockpitHostRegistry cockpitHostRegistry(CockpitProperties properties, Environment environment) {
        return new CockpitHostRegistry(environment.getProperty("horus.bpm.host-id", "self"), properties);
    }

    @Bean
    CockpitRegistryController cockpitRegistryController(CockpitHostRegistry registry) {
        return new CockpitRegistryController(registry);
    }

    @Bean
    CockpitWebMvcConfigurer cockpitWebMvcConfigurer(CockpitProperties properties) {
        return new CockpitWebMvcConfigurer(properties.path());
    }

    @Bean
    SmartInitializingSingleton cockpitBundleCheck(ResourceLoader resourceLoader, CockpitProperties properties) {
        return () -> {
            if (!resourceLoader.getResource(CockpitWebMvcConfigurer.BUNDLE_LOCATION + "index.html").exists()) {
                LOG.warn("The cockpit bundle is not on the classpath, so " + properties.path()
                        + " answers 404. Build cockpit/web (npm run build) before packaging the starter.");
            }
        };
    }
}

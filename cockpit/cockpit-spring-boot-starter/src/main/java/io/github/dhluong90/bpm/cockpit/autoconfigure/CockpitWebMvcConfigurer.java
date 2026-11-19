package io.github.dhluong90.bpm.cockpit.autoconfigure;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps the cockpit into the host's Spring MVC: the bundle from the jar under {@code path/**},
 * {@code path} redirected to {@code path/}, {@code path/} answered with {@code index.html}, and any
 * other route under {@code path/} answered with {@code index.html} too (history routing).
 */
final class CockpitWebMvcConfigurer implements WebMvcConfigurer {

    static final String BUNDLE_LOCATION = "classpath:/META-INF/horus-cockpit/";

    private final String path;

    CockpitWebMvcConfigurer(String path) {
        this.path = path;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(path + "/**")
                .addResourceLocations(BUNDLE_LOCATION)
                .resourceChain(true)
                .addResolver(new CockpitIndexFallbackResolver());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(path, path + "/");
        registry.addViewController(path + "/").setViewName("forward:" + path + "/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CockpitAccessInterceptor(path)).addPathPatterns(path, path + "/**");
    }
}

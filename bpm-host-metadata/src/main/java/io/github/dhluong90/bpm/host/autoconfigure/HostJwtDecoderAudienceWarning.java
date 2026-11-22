package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;

/**
 * Warns at start-up when the host defines its own {@code JwtDecoder} and an audience is configured (#451,
 * TR-SEC-12). Spring Boot's auto-configured decoder applies the starter's audience validator beside its issuer
 * check; a decoder the host builds itself does not, unless the host adds the {@code OAuth2TokenValidator<Jwt>}
 * beans to it. The starter does not rewrite the host's decoder, since replacing its validators could drop the
 * host's own checks; it names the bean instead. Silent on the {@code dev} profile and when no audience is set.
 */
final class HostJwtDecoderAudienceWarning implements SmartInitializingSingleton, BeanFactoryAware {

    static final String JWT_DECODER = "org.springframework.security.oauth2.jwt.JwtDecoder";
    private static final Log LOG = LogFactory.getLog("io.github.dhluong90.bpm.security");

    private final Environment environment;
    private ConfigurableListableBeanFactory beanFactory;

    HostJwtDecoderAudienceWarning(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory instanceof ConfigurableListableBeanFactory listable ? listable : null;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null || environment.matchesProfiles(EngineAccessAutoConfiguration.DEV_PROFILE)
                || !AudiencesConfigured.in(environment)) {
            return;
        }
        for (String bean : hostDecoders(beanFactory)) {
            LOG.warn("jwt.decoder.audience.unchecked bean=" + bean + ": this JwtDecoder is defined by the host, so "
                    + "no audience check is applied to it by the engines. A token issued for another host is "
                    + "accepted unless the decoder validates the aud claim. Add the OAuth2TokenValidator<Jwt> bean "
                    + "horusBpmAudienceValidator to its validators (TR-SEC-12).");
        }
    }

    /** The decoder beans not created by Spring Boot's own auto-configuration. */
    static List<String> hostDecoders(ConfigurableListableBeanFactory beanFactory) {
        Class<?> decoderType;
        try {
            decoderType = Class.forName(JWT_DECODER, false, HostJwtDecoderAudienceWarning.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return List.of();
        }
        List<String> hosts = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(decoderType, true, false)) {
            if (!beanFactory.containsBeanDefinition(name) || !springBootDefined(beanFactory.getBeanDefinition(name))) {
                hosts.add(name);
            }
        }
        return hosts;
    }

    private static boolean springBootDefined(BeanDefinition definition) {
        String origin = null;
        if (definition instanceof AnnotatedBeanDefinition annotated) {
            MethodMetadata factoryMethod = annotated.getFactoryMethodMetadata();
            origin = factoryMethod != null ? factoryMethod.getDeclaringClassName()
                    : annotated.getMetadata().getClassName();
        }
        if (origin == null) {
            origin = definition.getBeanClassName();
        }
        return origin != null && origin.startsWith("org.springframework.boot.");
    }
}

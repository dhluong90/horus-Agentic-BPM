package io.github.dhluong90.bpm.examples.ruleandprocess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ProtocolResolver;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

/**
 * Short timers for a live demo, in the {@code dev} profile only. The demo models carry their real durations ({@code P2D},
 * {@code PT2H}, {@code PT24H}); a presenter cannot wait for those. With {@code horus.demo.timers.<element id>} set, the
 * start-up import reads the model with that timer's {@code timeDuration} replaced, so the timer fires in the time given
 * (the dev profile sets {@code PT1M}). The files on disk and every other profile are untouched: without this
 * configuration no resolver is registered. Set a value to the model's own duration, or remove the entry, to keep it.
 *
 * @param timers ISO 8601 duration by the id of the boundary, catch or start event that holds the timer
 */
@ConfigurationProperties("horus.demo")
record DemoTimerOverrides(Map<String, Duration> timers) {

    private static final Pattern TIMER = Pattern.compile(
            "(<bpmn:\\w+\\s+id=\"%s\"[^>]*>\\s*<bpmn:timerEventDefinition>\\s*<bpmn:timeDuration>)[^<]*(</bpmn:timeDuration>)");

    DemoTimerOverrides {
        timers = timers == null ? Map.of() : Map.copyOf(timers);
        timers.forEach((id, duration) -> {
            if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("horus.demo.timers." + id + " must be at least PT1S");
            }
        });
    }

    /**
     * @param model the BPMN text
     * @return the text with each configured timer's duration replaced
     */
    String apply(String model) {
        String result = model;
        for (Map.Entry<String, Duration> entry : timers.entrySet()) {
            Pattern pattern = Pattern.compile(TIMER.pattern().formatted(Pattern.quote(entry.getKey())));
            result = pattern.matcher(result).replaceAll(
                    m -> Matcher.quoteReplacement(m.group(1) + entry.getValue() + m.group(2)));
        }
        return result;
    }

    /** Registers the resolver on the host's context before the start-up import reads any model. */
    @Configuration(proxyBeanMethods = false)
    @Profile("dev")
    @EnableConfigurationProperties(DemoTimerOverrides.class)
    static class Registration {

        @Bean
        static BeanFactoryPostProcessor demoTimerResolver() {
            return new Resolver();
        }
    }

    private static final class Resolver implements BeanFactoryPostProcessor, ApplicationContextAware {

        private ApplicationContext context;

        @Override
        public void setApplicationContext(ApplicationContext context) throws BeansException {
            this.context = context;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            Environment environment = context.getEnvironment();
            DemoTimerOverrides overrides = Binder.get(environment).bind("horus.demo", DemoTimerOverrides.class)
                    .orElseGet(() -> new DemoTimerOverrides(null));
            if (overrides.timers().isEmpty() || !(context instanceof GenericApplicationContext generic)) {
                return;
            }
            generic.addProtocolResolver(new ModelResolver(overrides, generic));
        }
    }

    /** Answers a demo model's text with the configured durations; every other location goes to the default loader. */
    private static final class ModelResolver implements ProtocolResolver {

        private final DemoTimerOverrides overrides;
        private final ResourceLoader loader;

        ModelResolver(DemoTimerOverrides overrides, ResourceLoader loader) {
            this.overrides = overrides;
            this.loader = loader;
        }

        @Override
        public Resource resolve(String location, ResourceLoader resourceLoader) {
            if (!location.startsWith("classpath:processes/") || !location.endsWith(".bpmn")) {
                return null;
            }
            Resource original = new org.springframework.core.io.ClassPathResource(
                    location.substring("classpath:".length()), loader.getClassLoader());
            try (var in = original.getInputStream()) {
                String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                return new ByteArrayResource(overrides.apply(text).getBytes(StandardCharsets.UTF_8), location);
            } catch (IOException | DateTimeException e) {
                throw new IllegalStateException("Cannot read the demo model " + location, e);
            }
        }
    }
}

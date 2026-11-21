package io.github.dhluong90.bpm.examples.models;

import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * A host that embeds both engines and registers one {@link AgentAdapter} bean per entry of
 * {@code example.model-adapters.adapters}: an OpenAI-compatible or an Anthropic Messages adapter, under the name steps
 * use in {@code horus:agent}. The engine's starter collects every {@code AgentAdapter} bean. The host's own sign-in,
 * which gives the engines their caller and tenant, is left out; see the {@code rule-and-process} example for that
 * part.
 */
@SpringBootApplication
public class ModelAdaptersApplication {

    /**
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ModelAdaptersApplication.class, args);
    }

    /**
     * Registers the configured adapters before the engine's starter collects them. The settings are bound and
     * checked here, so an invalid entry stops start-up naming its property, and every prompt template is loaded
     * once.
     *
     * @param environment the host's configuration
     * @return the registrar
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor modelAdapterRegistrar(Environment environment) {
        return registry -> {
            ModelAdapterSettings settings = Binder.get(environment).bind("example.model-adapters",
                    Bindable.of(ModelAdapterSettings.class)).orElseGet(() -> new ModelAdapterSettings(Map.of()));
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            ClassLoader loader = ModelAdaptersApplication.class.getClassLoader();
            settings.adapters().forEach((name, adapter) -> {
                PromptTemplate prompt = PromptTemplate.load(adapter.prompt(), loader);
                RootBeanDefinition definition = new RootBeanDefinition(AgentAdapter.class,
                        () -> create(name, adapter, prompt, http, clock(registry)));
                registry.registerBeanDefinition("modelAdapter." + name, definition);
            });
        };
    }

    private static Clock clock(BeanDefinitionRegistry registry) {
        return registry instanceof BeanFactory factory ? factory.getBeanProvider(Clock.class)
                .getIfUnique(Clock::systemUTC) : Clock.systemUTC();
    }

    static ModelApiAdapter create(String name, ModelAdapterSettings.Adapter adapter, PromptTemplate prompt,
            HttpClient http, Clock clock) {
        return switch (adapter.api()) {
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleAdapter(name, adapter, prompt, http, clock);
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesAdapter(name, adapter, prompt, http, clock);
        };
    }
}

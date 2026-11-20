package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.postgres.JdbcProcessStore;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineSchemaMigrator;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineTransactions;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.internal.service.LiveNodeCountService;
import io.github.dhluong90.bpm.process.rest.ProcessController;
import io.github.dhluong90.bpm.process.rest.ProcessProblemDetails;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configures the Process Engine on the host's {@link DataSource} and {@link PlatformTransactionManager}. Every
 * extension point has a default that a host bean of the same type replaces (ADR-0024). No component scan.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineAutoConfiguration"
}, beforeName = {
    "io.github.dhluong90.bpm.host.autoconfigure.EngineAccessAutoConfiguration",
    "io.github.dhluong90.bpm.host.autoconfigure.HostMetadataAutoConfiguration"
})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "horus.bpm.processes", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ProcessEngineProperties.class)
public class ProcessEngineAutoConfiguration {

    static final String DEV_PROFILE = "dev";
    static final List<String> PERMISSIONS = List.of("processes:read", "processes:deploy", "processes:start",
            "processes:task:work");
    private static final Log LOG = LogFactory.getLog(ProcessEngineAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineSchemaMigrator processEngineSchemaMigrator(DataSource dataSource) {
        return new ProcessEngineSchemaMigrator(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineTransactions processEngineTransactions(ObjectProvider<PlatformTransactionManager> transactionManager) {
        return new ProcessEngineTransactions(transactionManager.getIfUnique(() -> {
            throw new IllegalStateException("The Process Engine needs exactly one PlatformTransactionManager from the "
                    + "host. Add spring-boot-starter-jdbc, or mark one transaction manager @Primary.");
        }));
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcProcessStore processEngineStore(DataSource dataSource) {
        return new JdbcProcessStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEventPublisher processEngineEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessServices processEngineServices(ObjectProvider<TenantResolver> tenants, ObjectProvider<CallerResolver> callers,
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcProcessStore store,
            ObjectProvider<ServiceTaskHandler> handlers, ObjectProvider<RuleEngine> ruleEngine,
            ProcessEventPublisher publisher, ProcessEngineProperties properties, Environment environment) {
        if (properties.definitions().autoApprove()) {
            requireDevProfileOnly(environment, "horus.bpm.processes.definitions.auto-approve");
        }
        if (properties.definitions().autoApprove()) {
            LOG.warn("Process definitions are imported as ACTIVE without approval "
                    + "(horus.bpm.processes.definitions.auto-approve, dev profile).");
        }
        Map<String, ServiceTaskHandler> byName = new LinkedHashMap<>();
        handlers.orderedStream().forEach(h -> {
            if (byName.putIfAbsent(h.name(), h) != null) {
                throw new IllegalStateException("Two ServiceTaskHandler beans are named " + h.name());
            }
        });
        SubsetBpmnReader reader = new SubsetBpmnReader(new Limits(properties.definitions().maxBytes(),
                Limits.DEFAULTS.maxDepth(), Limits.DEFAULTS.maxElements(), Limits.DEFAULTS.maxForeignFragmentBytes(),
                Limits.DEFAULTS.maxTextChars(), Limits.DEFAULTS.maxAttributeChars()));
        return new ProcessServices(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC),
                transactions, store, Map.of(SourceFormat.BPMN_XML, reader::read),
                new LocalServiceInvoker(byName, Optional.ofNullable(ruleEngine.getIfUnique()),
                        properties.handlers().slowCallWarning()), publisher,
                properties.toSettings(), BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(EngineDefaults.CONDITION_CACHE_ENTRIES));
    }

    /**
     * A dev-only relaxation is accepted only when {@code dev} is an explicitly active profile and every other active
     * profile is {@code test}: {@code prod,dev} is refused, and so is {@code spring.profiles.default=dev} with no
     * active profile (PRE-022).
     *
     * @param environment the environment
     * @param property the relaxing property, named in the refusal
     */
    static void requireDevProfileOnly(Environment environment, String property) {
        List<String> active = List.of(environment.getActiveProfiles());
        boolean ok = active.contains(DEV_PROFILE) && active.stream().allMatch(p -> p.equals(DEV_PROFILE) || p.equals("test"));
        if (!ok) {
            throw new IllegalStateException(property + " relaxes a control and is accepted only when the dev profile is "
                    + "active on its own (optionally with test); active profiles are " + active + " (PRE-022).");
        }
    }

    @Bean
    @ConditionalOnMissingBean(DefinitionService.class)
    DefinitionService processDefinitionService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessEngine.class)
    ProcessEngine processEngine(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessQueryService.class)
    ProcessQueryService processQueryService(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcProcessStore store, ProcessEngineProperties properties) {
        return new LiveNodeCountService(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, properties.stats().toSettings());
    }

    @Bean
    @ConditionalOnMissingBean(TaskService.class)
    TaskService processTaskService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineInitializer processEngineInitializer(ProcessEngineSchemaMigrator migrator, ProcessServices services,
            ProcessEngineProperties properties, ResourceLoader resources) {
        return new ProcessEngineInitializer(migrator, services, properties, resources);
    }

    @Bean
    @ConditionalOnMissingBean(name = "processEngineModuleDescriptor")
    ModuleDescriptor processEngineModuleDescriptor(ProcessEngineProperties properties) {
        return new ModuleDescriptor("processes", ProcessEngineModule.engineVersion(), "v1", List.of(), Map.of(),
                PERMISSIONS, properties.security().rolePermissions());
    }

    /**
     * Default caller and tenant from the host contract's {@code EngineAccess} (bpm-host-metadata); a host bean of
     * the same type replaces each. Without a host contract every call is refused as unauthenticated.
     */
    @Bean
    @ConditionalOnMissingBean
    CallerResolver processEngineCallerResolver(ObjectProvider<EngineAccess> access) {
        return EngineAccessResolvers.callers(access::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    TenantResolver processEngineTenantResolver(ObjectProvider<EngineAccess> access) {
        return EngineAccessResolvers.tenants(access::getIfAvailable);
    }

    /** REST endpoints in a servlet host. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class RestConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ProcessController processEngineController(DefinitionService definitions, ProcessEngine engine, TaskService tasks,
                ProcessQueryService queries, ProcessEngineProperties properties) {
            return new ProcessController(definitions, engine, tasks, queries, properties.definitions().maxBytes());
        }

        @Bean
        @ConditionalOnMissingBean
        ProcessProblemDetails processEngineProblemDetails() {
            return new ProcessProblemDetails();
        }
    }
}

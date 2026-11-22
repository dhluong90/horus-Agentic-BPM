package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.StepCatalogue;
import io.github.dhluong90.bpm.process.api.StepCatalogueView;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The step catalogue (PRE-032, DESIGN section 5.7): the handlers, agent adapters, rule sets, calendars and roles the
 * host registered, and nothing else. Handlers and adapters come from the beans the host registered, rule sets from
 * the decision port (versions in effect on the engine clock), calendars from the tenant's stored calendars and roles
 * from the host's {@link RoleCatalogue}. The tenant is the resolver's, never a request value; the read writes nothing
 * and needs {@code processes:author}. Thread-safe.
 */
public final class StepCatalogues implements StepCatalogue {

    static final String PERMISSION = "processes:author";
    /** Most rule sets, calendars and roles one catalogue lists. */
    static final int MAX_ENTRIES = 500;
    private static final String SYSTEM_PREFIX = "system:";

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final CalendarStore calendars;
    private final Collection<ServiceTaskHandler> handlers;
    private final Collection<ServiceAdapter> adapters;
    private final Collection<AgentAdapter> agents;
    private final Optional<DecisionPort> decisions;
    private final RoleCatalogue roles;

    /**
     * @param tenants the tenant of the call
     * @param callers the caller and their permissions
     * @param clock the engine clock, which selects the rule set versions in effect
     * @param transactions runs the calendar read
     * @param calendars the stored calendars
     * @param handlers the host's local handlers
     * @param adapters the host's remote service adapters
     * @param agents the host's agent adapters
     * @param decisions the decision port, empty without a Rule Engine
     * @param roles the host's role list
     */
    public StepCatalogues(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            CalendarStore calendars, Collection<ServiceTaskHandler> handlers, Collection<ServiceAdapter> adapters,
            Collection<AgentAdapter> agents, Optional<DecisionPort> decisions, RoleCatalogue roles) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.calendars = Objects.requireNonNull(calendars, "calendars must not be null");
        this.handlers = List.copyOf(handlers);
        this.adapters = List.copyOf(adapters);
        this.agents = List.copyOf(agents);
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
    }

    @Override
    public StepCatalogueView stepCatalogue() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (caller.startsWith(SYSTEM_PREFIX) || !callers.currentPermissions().contains(PERMISSION)) {
            throw new PermissionDeniedException("Needs " + PERMISSION);
        }
        List<StepCatalogueView.Handler> steps = new ArrayList<>();
        handlers.forEach(h -> steps.add(new StepCatalogueView.Handler(h.name(), "local", h.inputSchema(),
                h.compensable())));
        adapters.forEach(a -> steps.add(new StepCatalogueView.Handler(a.name(), "remote", a.inputSchema(),
                a.compensable())));
        steps.sort(Comparator.comparing(StepCatalogueView.Handler::name).thenComparing(StepCatalogueView.Handler::mode));
        List<StepCatalogueView.Agent> agentEntries = new ArrayList<>(agents.stream()
                .map(a -> new StepCatalogueView.Agent(a.name(), a.inputSchema())).toList());
        agentEntries.sort(Comparator.comparing(StepCatalogueView.Agent::name));
        List<StepCatalogueView.RuleSet> decisionSets = new ArrayList<>();
        List<StepCatalogueView.RuleSet> guardrails = new ArrayList<>();
        decisions.ifPresent(port -> ruleSets(port, tenant, decisionSets, guardrails));
        List<String> keys = transactions.inTransaction(() -> calendars.calendars(tenant, null, MAX_ENTRIES + 1)
                .stream().map(CalendarStore.SummaryRow::key).limit(MAX_ENTRIES).toList());
        List<String> roleNames = roles.roles(tenant).stream().distinct().sorted().limit(MAX_ENTRIES).toList();
        return new StepCatalogueView(steps, agentEntries, decisionSets, guardrails, keys, roleNames);
    }

    private void ruleSets(DecisionPort port, String tenant, List<StepCatalogueView.RuleSet> decisionSets,
            List<StepCatalogueView.RuleSet> guardrails) {
        var now = clock.instant();
        for (String name : port.ruleSets(tenant, now).stream().sorted().limit(MAX_ENTRIES).toList()) {
            if (port.signature(tenant, name, now) instanceof DecisionSignature.Found found) {
                StepCatalogueView.RuleSet entry = new StepCatalogueView.RuleSet(found.ruleSet(), found.version(),
                        found.hitPolicy(), found.inputs(), found.outputs());
                decisionSets.add(entry);
                if (GuardrailContract.meetsOutputContract(found)) {
                    guardrails.add(entry);
                }
            }
        }
    }
}

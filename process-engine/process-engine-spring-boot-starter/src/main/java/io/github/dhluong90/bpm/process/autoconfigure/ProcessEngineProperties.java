package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import io.github.dhluong90.bpm.process.internal.service.LiveStatsSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Process Engine under {@code horus.bpm.processes} (ADR-0024). An invalid value fails start-up
 * naming the property. Limits that protect the host database may be lowered, never raised.
 *
 * @param enabled turn the engine off in this host
 * @param migrateOnStart apply the {@code pre_*} migrations at start-up, recorded in {@code pre_schema_history}
 * @param definitions definition import settings
 * @param idempotencyRetention how long an idempotency record answers replays
 * @param security mapping of the host's roles to engine permissions
 * @param handlers local step settings
 * @param jobs timer and job settings
 * @param makerChecker four eyes on definition approval
 * @param query read limits of the list endpoints
 * @param lifecycle definition and instance lifecycle settings
 * @param stats live count settings
 * @param adapters remote step settings (Async Wait-State, ADR-0008)
 * @param runners per async job kind, the settings of its runner (#363); unset values fall back to {@code adapters.*}
 *     and {@code jobs.*}
 * @param agents agent step settings (#59)
 */
@ConfigurationProperties("horus.bpm.processes")
public record ProcessEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean migrateOnStart,
        @DefaultValue Definitions definitions,
        @DefaultValue("PT24H") Duration idempotencyRetention,
        @DefaultValue Security security,
        @DefaultValue Handlers handlers,
        @DefaultValue Jobs jobs,
        @DefaultValue MakerChecker makerChecker,
        @DefaultValue Query query,
        @DefaultValue Lifecycle lifecycle,
        @DefaultValue Stats stats,
        @DefaultValue Adapters adapters,
        @DefaultValue Runners runners,
        @DefaultValue Agents agents) {

    public ProcessEngineProperties {
        if (idempotencyRetention.isNegative() || idempotencyRetention.isZero()) {
            throw new IllegalArgumentException("horus.bpm.processes.idempotency-retention must be positive");
        }
    }

    /**
     * @param locations BPMN files imported at start-up, for example {@code classpath:processes/card-fee-review.bpmn}
     * @param startupTenants tenants the start-up import runs for; empty means no start-up import
     * @param autoApprove import as {@code ACTIVE} without approval; refused outside the {@code dev} profile
     * @param maxNodes most flow nodes per definition; may be lowered, never raised
     * @param maxBytes largest definition document in bytes; may be lowered, never raised
     */
    public record Definitions(
            @DefaultValue List<String> locations,
            @DefaultValue List<String> startupTenants,
            @DefaultValue("false") boolean autoApprove,
            @DefaultValue("500") int maxNodes,
            @DefaultValue("1048576") int maxBytes) {

        public Definitions {
            if (maxNodes < 2 || maxNodes > EngineDefaults.MAX_NODES) {
                throw new IllegalArgumentException("horus.bpm.processes.definitions.max-nodes must be 2 to "
                        + EngineDefaults.MAX_NODES + " (it may be lowered, never raised)");
            }
            if (maxBytes < 1024 || maxBytes > EngineDefaults.MAX_BYTES) {
                throw new IllegalArgumentException("horus.bpm.processes.definitions.max-bytes must be 1024 to "
                        + EngineDefaults.MAX_BYTES + " (it may be lowered, never raised)");
            }
            locations = List.copyOf(locations);
            startupTenants = List.copyOf(startupTenants);
        }
    }

    /**
     * @param slowCallWarning a local handler or decision that takes longer is logged at WARN: it holds the instance
     *     row lock and a pool connection for its whole duration, which usually means a network call in a handler
     */
    public record Handlers(@DefaultValue("200ms") Duration slowCallWarning) {

        public Handlers {
            if (slowCallWarning.isNegative() || slowCallWarning.isZero()) {
                throw new IllegalArgumentException("horus.bpm.processes.handlers.slow-call-warning must be positive");
            }
        }
    }

    /**
     * @param enabled the requester of a definition version can never approve it; {@code false} is accepted on the
     *     {@code dev} profile only (TR-SEC-11, PRE-022), and a self-approval is then recorded as
     *     {@code system:dev-auto-approve}
     */
    public record MakerChecker(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Definition lifecycle settings. Whom the approver must differ from is not here: it is the shared host setting
     * {@code horus.bpm.lifecycle.approver-must-differ-from} (PD-096), read for both engines by
     * {@code ApproverMustDifferFrom} in the host module.
     *
     * @param reasonMaxLength longest reason or approval comment, which land in the immutable history, and longest
     *     cancellation reason; 2,000 by default, may be lowered, never raised (PD-103)
     */
    public record Lifecycle(@DefaultValue("2000") int reasonMaxLength) {

        public Lifecycle {
            if (reasonMaxLength < 1 || reasonMaxLength > EngineDefaults.MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("horus.bpm.processes.lifecycle.reason-max-length must be 1 to "
                        + EngineDefaults.MAX_REASON_LENGTH + " (it may be lowered, never raised, PD-103)");
            }
        }
    }

    /**
     * Timers and other jobs in {@code pre_timer}, claimed replica-safe with {@code FOR UPDATE SKIP LOCKED} and a lease
     * (PRE-006, DESIGN sections 4 and 6.5).
     *
     * @param enabled run the job poller in this replica; a host may turn it off in replicas that should serve
     *     requests only, as long as one replica polls
     * @param replicaId the name this replica claims jobs under; empty means the host name plus a random suffix, unique
     *     per start
     * @param pollInterval pause between polls when the last poll found no due job
     * @param batchSize most jobs one poll claims
     * @param timerLease how long a claim of a timer holds it; another replica claims it after the lease when this
     *     replica stopped
     * @param maxAttempts claims after which a job that keeps failing is marked {@code FAILED}
     * @param retryBackoff a failed firing is due again after this times the attempt number
     * @param leaseMargin added to a remote step's timeout to give the lease of its job, so a live attempt is never
     *     taken over (ADR-0008, PRE-017)
     */
    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String replicaId,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("32") int batchSize,
            @DefaultValue("60s") Duration timerLease,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("10s") Duration retryBackoff,
            @DefaultValue("30s") Duration leaseMargin) {

        public Jobs {
            if (leaseMargin.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.lease-margin must be at least 1s");
            }
            if (pollInterval.compareTo(Duration.ofMillis(10)) < 0 || pollInterval.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.poll-interval must be 10ms to 10m");
            }
            if (batchSize < 1 || batchSize > 1_000) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.batch-size must be 1 to 1000");
            }
            if (timerLease.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.timer-lease must be at least 1s");
            }
            if (maxAttempts < 1 || maxAttempts > 100) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.max-attempts must be 1 to 100");
            }
            if (retryBackoff.isNegative() || retryBackoff.isZero()) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.retry-backoff must be positive");
            }
            if (replicaId.length() > 200) {
                throw new IllegalArgumentException("horus.bpm.processes.jobs.replica-id must be at most 200 characters");
            }
        }

        JobSettings toSettings(String defaultReplicaId) {
            return new JobSettings(replicaId.isBlank() ? defaultReplicaId : replicaId, timerLease, batchSize, maxAttempts,
                    retryBackoff);
        }
    }

    /**
     * Remote steps ({@code serviceTask horus:mode="remote"}), run as an Async Wait-State (ADR-0008, DESIGN section 4).
     *
     * @param defaultTimeout timeout of a remote step that declares no {@code horus:timeout}; the lease of its job is
     *     this plus {@code jobs.lease-margin}
     * @param executorThreads adapter calls in flight per replica; a job is claimed only when one of these is free
     * @param completerRoles per adapter name (the {@code horus:handler} value; write a name with dots as
     *     {@code "[creditBureau.score]"}), the roles that may complete its steps through
     *     {@code POST /async-steps/{jobId}:complete}, on top of {@code processes:step:complete}. An adapter without an
     *     entry cannot be completed from outside. A name that is no registered adapter stops start-up
     */
    public record Adapters(
            @DefaultValue("PT30S") Duration defaultTimeout,
            @DefaultValue("16") int executorThreads,
            @DefaultValue Map<String, List<String>> completerRoles) {

        public Adapters {
            if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
                throw new IllegalArgumentException("horus.bpm.processes.adapters.default-timeout must be positive");
            }
            if (executorThreads < 1 || executorThreads > 1_000) {
                throw new IllegalArgumentException("horus.bpm.processes.adapters.executor-threads must be 1 to 1000");
            }
        }
    }

    /**
     * @return the async step settings
     */
    io.github.dhluong90.bpm.process.internal.service.AsyncSettings toAsyncSettings() {
        Map<String, java.util.Set<String>> roles = new java.util.LinkedHashMap<>();
        (adapters.completerRoles() == null ? Map.<String, List<String>>of() : adapters.completerRoles())
                .forEach((adapter, names) -> roles.put(adapter, java.util.Set.copyOf(names == null ? List.of() : names)));
        Map<String, io.github.dhluong90.bpm.process.internal.service.RunnerSettings.Overrides> kinds =
                new java.util.LinkedHashMap<>();
        kinds.put(io.github.dhluong90.bpm.process.internal.store.JobStore.ASYNC_SERVICE,
                runners.asyncService().toOverrides());
        kinds.put(io.github.dhluong90.bpm.process.internal.store.JobStore.ASYNC_AGENT,
                runners.asyncAgent().toOverrides());
        return new io.github.dhluong90.bpm.process.internal.service.AsyncSettings(jobs.leaseMargin(),
                adapters.defaultTimeout(), adapters.executorThreads(), roles, kinds);
    }

    /**
     * Agent step settings (#59, DESIGN section 4.1).
     *
     * @param labels bounds of the labels an agent reports with its answer
     */
    public record Agents(@DefaultValue Labels labels) {
    }

    /**
     * Bounds of the labels an agent reports with its answer (PD-014), such as {@code model} and
     * {@code promptVersion}. History records them as they are and the engine never acts on one; an answer above the
     * bounds is a failed attempt with {@code AGENT_LABELS_INVALID}. They may be lowered, never raised.
     *
     * @param maxCount labels per answer, 0 to 16
     * @param maxKeyLength characters of a label key, 1 to 64
     * @param maxValueLength characters of a label value, 1 to 256
     */
    public record Labels(@DefaultValue("16") int maxCount, @DefaultValue("64") int maxKeyLength,
            @DefaultValue("256") int maxValueLength) {

        public Labels {
            try {
                new io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits(maxCount, maxKeyLength,
                        maxValueLength);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage()
                        + " (it may be lowered, never raised)", e);
            }
        }

        /** @return the validated bounds */
        io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits toLimits() {
            return new io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits(maxCount, maxKeyLength,
                    maxValueLength);
        }
    }

    /**
     * One runner per async job kind (#363, DESIGN sections 4 and 6.5). Each kind has its own slots and its own claim,
     * so a backlog of one kind never delays another. Timers keep {@code jobs.*}.
     *
     * @param asyncService the runner of remote service steps ({@code serviceTask horus:mode="remote"})
     * @param asyncAgent the runner of agent steps
     */
    public record Runners(@DefaultValue Runner asyncService, @DefaultValue Runner asyncAgent) {
    }

    /**
     * The settings of one runner. Every value is optional; an unset value falls back to the engine-wide key, so a
     * host that sets nothing runs every kind as before #363.
     *
     * @param slots calls of this kind in flight per replica, 1 to 1000; falls back to {@code adapters.executor-threads}
     * @param defaultTimeout timeout of a step of this kind that declares none; falls back to
     *     {@code adapters.default-timeout}
     * @param maxAttempts claims after which a failing job of this kind is {@code FAILED}, 1 to 100; falls back to
     *     {@code jobs.max-attempts}
     * @param retryBackoff a failed attempt of this kind is due again after this times the attempt number; falls back
     *     to {@code jobs.retry-backoff}
     * @param guardBudget longest time the output-schema and guardrail check of a result may take in the result
     *     transaction, at most 10s; a slower check refuses the result (fails closed). Default 500ms
     * @param schemaRetries how often a result that breaks its output schema gets a fresh attempt before it is refused
     *     with {@code AGENT_SCHEMA_INVALID} and goes to review or fails, 0 to 5; counted apart from
     *     {@code max-attempts}. Default 1 for agent steps and 0 for remote service steps
     */
    public record Runner(Integer slots, Duration defaultTimeout, Integer maxAttempts, Duration retryBackoff,
            Duration guardBudget, Integer schemaRetries) {

        public Runner {
            if (slots != null && (slots < 1 || slots > 1_000)) {
                throw new IllegalArgumentException("horus.bpm.processes.runners.<kind>.slots must be 1 to 1000");
            }
            if (defaultTimeout != null && (defaultTimeout.isNegative() || defaultTimeout.isZero())) {
                throw new IllegalArgumentException("horus.bpm.processes.runners.<kind>.default-timeout must be positive");
            }
            if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > 100)) {
                throw new IllegalArgumentException("horus.bpm.processes.runners.<kind>.max-attempts must be 1 to 100");
            }
            if (retryBackoff != null && (retryBackoff.isNegative() || retryBackoff.isZero())) {
                throw new IllegalArgumentException("horus.bpm.processes.runners.<kind>.retry-backoff must be positive");
            }
            if (guardBudget != null && (guardBudget.isNegative() || guardBudget.isZero()
                    || guardBudget.compareTo(Duration.ofSeconds(10)) > 0)) {
                throw new IllegalArgumentException(
                        "horus.bpm.processes.runners.<kind>.guard-budget must be positive and at most 10s");
            }
            if (schemaRetries != null && (schemaRetries < 0
                    || schemaRetries > io.github.dhluong90.bpm.process.internal.service.RunnerSettings.MAX_SCHEMA_RETRIES)) {
                throw new IllegalArgumentException("horus.bpm.processes.runners.<kind>.schema-retries must be 0 to "
                        + io.github.dhluong90.bpm.process.internal.service.RunnerSettings.MAX_SCHEMA_RETRIES);
            }
        }

        io.github.dhluong90.bpm.process.internal.service.RunnerSettings.Overrides toOverrides() {
            return new io.github.dhluong90.bpm.process.internal.service.RunnerSettings.Overrides(slots, defaultTimeout,
                    maxAttempts, retryBackoff, guardBudget, schemaRetries);
        }
    }

    /**
     * @param maxPageSize most items per list page; may be lowered, never raised
     * @param maxRange longest start-time range of a list query; may be lowered, never raised
     */
    public record Query(@DefaultValue("200") int maxPageSize, @DefaultValue("P31D") Duration maxRange) {
        public Query {
            if (maxPageSize < 1 || maxPageSize > EngineDefaults.MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("horus.bpm.processes.query.max-page-size must be 1 to "
                        + EngineDefaults.MAX_PAGE_SIZE + " (it may be lowered, never raised)");
            }
            if (maxRange.isNegative() || maxRange.isZero() || maxRange.compareTo(EngineDefaults.MAX_RANGE) > 0) {
                throw new IllegalArgumentException("horus.bpm.processes.query.max-range must be positive and at most "
                        + EngineDefaults.MAX_RANGE + " (it may be lowered, never raised)");
            }
        }
    }

    /**
     * @param liveCacheTtl how long a replica answers live counts per node from its cache
     * @param liveCacheTtlLarge the cache time when the last count exceeded {@code live-cache-large-threshold}
     * @param liveCacheLargeThreshold live tokens of one definition version above which {@code live-cache-ttl-large}
     *     applies
     */
    public record Stats(
            @DefaultValue("PT5S") Duration liveCacheTtl,
            @DefaultValue("PT15S") Duration liveCacheTtlLarge,
            @DefaultValue("100000") long liveCacheLargeThreshold) {

        public Stats {
            if (liveCacheTtl.isNegative() || liveCacheTtlLarge.isNegative()) {
                throw new IllegalArgumentException("horus.bpm.processes.stats.live-cache-ttl and live-cache-ttl-large "
                        + "must not be negative");
            }
            if (liveCacheLargeThreshold < 1) {
                throw new IllegalArgumentException("horus.bpm.processes.stats.live-cache-large-threshold must be positive");
            }
        }

        LiveStatsSettings toSettings() {
            return new LiveStatsSettings(liveCacheTtl, liveCacheTtlLarge, liveCacheLargeThreshold);
        }
    }

    /**
     * @param rolePermissions host authority (for example {@code ROLE_CARD_OFFICER}) to engine permissions
     */
    public record Security(@DefaultValue Map<String, List<String>> rolePermissions) {
    }

    EngineSettings toSettings(EngineSettings.ApproverRule approverRule,
            EngineSettings.RetireLastVersionRule retireLastVersion) {
        return new EngineSettings(definitions.autoApprove(), definitions.maxNodes(), definitions.maxBytes(),
                idempotencyRetention, handlers.slowCallWarning(), query.maxPageSize(), query.maxRange(),
                lifecycle.reasonMaxLength(), makerChecker.enabled(), approverRule, retireLastVersion);
    }
}

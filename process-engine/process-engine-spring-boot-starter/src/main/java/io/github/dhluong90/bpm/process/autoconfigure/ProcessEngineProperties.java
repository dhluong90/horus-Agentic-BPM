package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import io.github.dhluong90.bpm.process.internal.service.LiveStatsSettings;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import io.github.dhluong90.bpm.process.internal.service.ReplaySettings;
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
 * @param idempotency the wait of a request on a key another request holds (#172)
 * @param security mapping of the host's roles to engine permissions
 * @param handlers local step settings
 * @param jobs timer and job settings
 * @param makerChecker four eyes on definition approval
 * @param query read limits of the list endpoints
 * @param lifecycle definition and instance lifecycle settings
 * @param stats live count and hourly rollup settings
 * @param metrics operational metrics settings
 * @param adapters remote step settings (Async Wait-State, ADR-0008)
 * @param runners per async job kind, the settings of its runner (#363); unset values fall back to {@code adapters.*}
 *     and {@code jobs.*}
 * @param agents agent step settings (#59)
 * @param replay offline replay of agent answers (#98)
 * @param calendar business calendar changes (#72)
 * @param migration migration plans of running instances (#74)
 * @param approval what a definition approval must name (PD-139)
 * @param incidents how incidents are resolved (PD-126)
 * @param classification the reminder about agent step fields that declare no {@code mask} (PD-146)
 * @param calls limits of a process that calls another (#92)
 * @param tasks how a user task is completed through a declared form (PRE-031)
 * @param database the schema owner and the application role (#700)
 * @param parallelStart limits of the arrivals a parallel multiple start holds (PD-149, AUD-10)
 * @param variableEncryption which instance variables are encrypted at rest and under which keys (PRE-395)
 */
@ConfigurationProperties("horus.bpm.processes")
public record ProcessEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean migrateOnStart,
        @DefaultValue Definitions definitions,
        @DefaultValue("PT24H") Duration idempotencyRetention,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Security security,
        @DefaultValue Handlers handlers,
        @DefaultValue Jobs jobs,
        @DefaultValue MakerChecker makerChecker,
        @DefaultValue Query query,
        @DefaultValue Lifecycle lifecycle,
        @DefaultValue Stats stats,
        @DefaultValue Metrics metrics,
        @DefaultValue Adapters adapters,
        @DefaultValue Runners runners,
        @DefaultValue Agents agents,
        @DefaultValue Replay replay,
        @DefaultValue Calendar calendar,
        @DefaultValue Migration migration,
        @DefaultValue Approval approval,
        @DefaultValue Incidents incidents,
        @DefaultValue Classification classification,
        @DefaultValue Calls calls,
        @DefaultValue MultiInstance multiInstance,
        @DefaultValue Tasks tasks,
        @DefaultValue Cursor cursor,
        @DefaultValue ParallelStart parallelStart,
        @DefaultValue Database database,
        @DefaultValue VariableEncryption variableEncryption) {

    /**
     * Two database roles for production (#700): a schema owner that migrates and an application role that serves traffic.
     * Unset, the engine migrates and runs as the one role of the host's {@code DataSource}, which suits development and
     * is not for production, because that role owns the tables and can disable their triggers.
     *
     * @param ownerUrl JDBC URL of the schema owner's connection; required with {@code ownerUsername}
     * @param ownerUsername the schema owner; set it to turn the two-role setup on. Do not set it on a serving host: run the migration as a separate step ({@code horus.bpm.migrate-only=true}, connecting as the owner) and give the serving hosts the application role only. The owner migrates at start-up (and
     *     keeps the partition step, when the engine has one) and is not used for requests: its connections are opened
     *     for one run and closed
     * @param ownerPassword the owner's password; supply it from a secret, never from a file in the repository
     * @param appRole the role the host's {@code DataSource} connects as; after each migration the engine grants it
     *     SELECT, INSERT, UPDATE and DELETE on the engine's tables and nothing else. Empty grants nothing: apply
     *     {@code deploy/postgres/app-role-grants.sql} yourself
     * @param roleCheck what start-up does when the role the host serves traffic with owns the engine's tables, holds
     *     TRIGGER on them or is a superuser: {@code enforce} (default outside the {@code dev} profile) stops the start with the code {@code HORUS-DB-ROLE-001}, {@code warn} logs one WARN,
     *     {@code off} skips the check. Outside {@code dev}, {@code warn} and {@code off} are an opt-out that logs {@code HORUS-DB-ROLE-002} at ERROR on every start. In the {@code dev} profile the default is {@code off}. The check is skipped when {@code horus.bpm.migrate-only=true}
     */
    public record Database(String ownerUrl, String ownerUsername, String ownerPassword, String appRole,
            @DefaultValue("") String roleCheck) {

        /** Whether the two-role setup is on. */
        public boolean split() {
            return ownerUsername != null && !ownerUsername.isBlank();
        }

        /** Never prints the password. */
        @Override
        public String toString() {
            return "Database[ownerUrl=" + ownerUrl + ", ownerUsername=" + ownerUsername + ", ownerPassword=***, appRole="
                    + appRole + ", roleCheck=" + roleCheck + "]";
        }
    }

    /**
     * Application-level encryption of instance variables (PRE-395). Off until {@code variables} or
     * {@code definitions} name something. Supply every key from an environment variable or a secret store, never from a
     * file in the repository. Naming a variable without a key fails start-up: the engine never stores it in the clear
     * instead.
     *
     * @param variables names of the variables encrypted in every definition, matched on the top-level name
     * @param definitions per definition key, the variable names encrypted there; {@code *} encrypts all of them
     * @param activeKeyId the id of the key that encrypts new values, 1 to 32 letters, digits, {@code .}, {@code _} or
     *     {@code -}; required when {@code variables} or {@code definitions} is set
     * @param keys Base64 AES-256 keys (32 bytes after decoding) by id. To rotate: add a new key, make it active, run
     *     the re-encryption sweep, then remove the old key once no stored value carries its id; a value of a removed
     *     key is refused with {@code 500 VARIABLE_KEY_UNAVAILABLE}
     */
    public record VariableEncryption(java.util.Set<String> variables,
            java.util.Map<String, java.util.Set<String>> definitions, String activeKeyId,
            java.util.Map<String, String> keys) {

        /** Copies the collections; unset is empty. */
        public VariableEncryption {
            variables = variables == null ? java.util.Set.of() : java.util.Set.copyOf(variables);
            definitions = definitions == null ? java.util.Map.of() : java.util.Map.copyOf(definitions);
            keys = keys == null ? java.util.Map.of() : java.util.Map.copyOf(keys);
        }

        /** @return the rules these settings describe */
        public io.github.dhluong90.bpm.process.spi.VariableEncryptionRules rules() {
            return new io.github.dhluong90.bpm.process.spi.VariableEncryptionRules(variables, definitions);
        }

        /**
         * Builds the keyring: the active key writes, every other key only reads.
         *
         * @return the keyring, empty when no key is set
         * @throws IllegalArgumentException naming the property when a key is not Base64 or not 32 bytes, or the active
         *     id is missing
         */
        public io.github.dhluong90.bpm.process.spi.VariableKeyring keyring() {
            if (keys.isEmpty()) {
                return io.github.dhluong90.bpm.process.spi.VariableKeyring.none();
            }
            if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
                throw new IllegalArgumentException("horus.bpm.processes.variable-encryption.active-key-id must name "
                        + "one of horus.bpm.processes.variable-encryption.keys");
            }
            java.util.List<io.github.dhluong90.bpm.process.spi.VariableKeyring.Key> earlier = new java.util.ArrayList<>();
            io.github.dhluong90.bpm.process.spi.VariableKeyring.Key active = null;
            for (java.util.Map.Entry<String, String> e : keys.entrySet()) {
                io.github.dhluong90.bpm.process.spi.VariableKeyring.Key key;
                try {
                    key = new io.github.dhluong90.bpm.process.spi.VariableKeyring.Key(e.getKey(),
                            java.util.Base64.getDecoder().decode(e.getValue().strip()));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("horus.bpm.processes.variable-encryption.keys." + e.getKey()
                            + " must be Base64 of exactly 32 bytes");
                }
                if (e.getKey().equals(activeKeyId)) {
                    active = key;
                } else {
                    earlier.add(key);
                }
            }
            io.github.dhluong90.bpm.process.spi.VariableKeyring.Key now = active;
            return new io.github.dhluong90.bpm.process.spi.VariableKeyring() {
                @Override
                public java.util.Optional<Key> current() {
                    return java.util.Optional.of(now);
                }

                @Override
                public java.util.List<Key> previous() {
                    return earlier;
                }
            };
        }

        /** Never prints a key. */
        @Override
        public String toString() {
            return "VariableEncryption[variables=" + variables + ", definitions=" + definitions.keySet()
                    + ", activeKeyId=" + activeKeyId + ", keys=" + keys.keySet() + "]";
        }
    }

    /**
     * The keys that sign list cursors (AUD-13). Supply every key from an environment variable or a secret store,
     * never from a file in the repository. When no key is set the engine keeps one random key in
     * {@code pre_engine_key}, which anyone who can read the database can use to forge a cursor; set keys in
     * production.
     *
     * @param activeKeyId the id of the key that signs new cursors, 1 to 32 letters, digits, {@code _} or {@code -};
     *     required when {@code keys} is set
     * @param keys Base64 keys of at least 32 bytes after decoding, by id. To rotate, add a new key, make it active,
     *     and remove the old one after the cursors it signed are no longer in use; a cursor of a removed key is
     *     {@code 400 QUERY_INVALID}
     */
    public record Cursor(String activeKeyId, java.util.Map<String, String> keys) {

        /** Copies the keys; unset is empty. */
        public Cursor {
            keys = keys == null ? java.util.Map.of() : java.util.Map.copyOf(keys);
        }

        /**
         * Decodes the keys.
         *
         * @return the keys by id, empty when none is configured
         * @throws IllegalArgumentException naming the property when a key is not Base64 or the active id is missing
         */
        public java.util.Map<String, byte[]> decoded() {
            if (keys.isEmpty()) {
                return java.util.Map.of();
            }
            if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
                throw new IllegalArgumentException("horus.bpm.processes.cursor.active-key-id must name one of "
                        + "horus.bpm.processes.cursor.keys");
            }
            java.util.Map<String, byte[]> out = new java.util.HashMap<>();
            keys.forEach((id, value) -> {
                try {
                    out.put(id, java.util.Base64.getDecoder().decode(value.strip()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("horus.bpm.processes.cursor.keys." + id + " is not Base64");
                }
            });
            return out;
        }

        /** Never prints a key. */
        @Override
        public String toString() {
            return "Cursor[activeKeyId=" + activeKeyId + ", keys=" + keys.keySet() + "]";
        }
    }

    /**
     * Arrivals held for a process whose start event waits for several messages or signals (PD-149, AUD-10).
     *
     * @param arrivalTtl how long an arrival waits for the others before it is deleted, 1 minute to 366 days; default
     *     24 hours
     * @param maxArrivalsPerDefinition most arrivals held for one definition of one tenant; a new correlation key past
     *     it is refused with {@code 429 START_ARRIVALS_FULL} and a {@code Retry-After} header; 1 to 1,000,000, default 10,000
     * @param cleanupInterval how often expired arrivals are deleted, 1 second to 1 day; default 15 minutes
     */
    public record ParallelStart(
            @DefaultValue("PT24H") Duration arrivalTtl,
            @DefaultValue("10000") int maxArrivalsPerDefinition,
            @DefaultValue("PT15M") Duration cleanupInterval) {

        public ParallelStart {
            try {
                new io.github.dhluong90.bpm.process.internal.service.StartArrivalSettings(arrivalTtl,
                        maxArrivalsPerDefinition);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage(), e);
            }
            if (cleanupInterval == null || cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || cleanupInterval.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException(
                        "horus.bpm.processes.parallel-start.cleanup-interval must be 1 second to 1 day");
            }
        }

        io.github.dhluong90.bpm.process.internal.service.StartArrivalSettings toSettings() {
            return new io.github.dhluong90.bpm.process.internal.service.StartArrivalSettings(arrivalTtl,
                    maxArrivalsPerDefinition);
        }
    }

    /**
     * Where a user task's declared form comes from and where its link may point (PRE-031, PRE-219).
     *
     * @param formResourceRoot classpath folder the default {@code FormRegistry} reads form documents from, ending in
     *     {@code /}; default {@code forms/}. A host that registers its own {@code FormRegistry} ignores it
     * @param formLinkOrigins origins a form link may point to besides relative links, for example
     *     {@code https://loans.example.com}; empty by default, so only relative links are accepted. An origin is
     *     {@code http} or {@code https}, a host and an optional port, with no path
     */
    public record Tasks(
            @DefaultValue("forms/") String formResourceRoot,
            @DefaultValue List<String> formLinkOrigins) {

        private static final java.util.regex.Pattern ORIGIN = java.util.regex.Pattern.compile(
                "^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

        public Tasks {
            if (!formResourceRoot.endsWith("/") || formResourceRoot.startsWith("/") || formResourceRoot.contains("..")) {
                throw new IllegalArgumentException("horus.bpm.processes.tasks.form-resource-root must be a relative "
                        + "classpath folder ending in /, was " + formResourceRoot);
            }
            formLinkOrigins = List.copyOf(formLinkOrigins);
            for (String origin : formLinkOrigins) {
                if (!ORIGIN.matcher(origin).matches()) {
                    throw new IllegalArgumentException("horus.bpm.processes.tasks.form-link-origins holds " + origin
                            + ", which is not an origin such as https://loans.example.com");
                }
            }
        }
    }

    /**
     * The reminder about agent step fields with no classification (PD-146, #438). Masking stays opt-in: an agent
     * step's {@code horus:input} is masked only with {@code mask="true"}, an output property only with
     * {@code "mask": true}. With the reminder on, every such field that declares neither true nor false is listed in
     * the {@code warnings} of the version on {@code GET .../versions/{v}} and {@code :request-approval}, and outside
     * {@code dev} one start-up WARN counts them per active version. It never blocks and never looks at names or values.
     *
     * @param warnUnclassified whether to warn; default {@code true}. Set {@code false} for a deployment that classifies
     *     fields elsewhere
     */
    public record Classification(@DefaultValue("true") boolean warnUnclassified) {
    }

    /**
     * @param lockTimeout the longest a request waits for an idempotency key another request still holds, before it
     *     gets {@code 409 IDEMPOTENCY_IN_FLIGHT} with {@code Retry-After: 1}; 1 millisecond to 2 seconds (the default),
     *     lowered only, because a waiting request holds a pool connection
     */
    public record Idempotency(@DefaultValue("PT2S") Duration lockTimeout) {

        public Idempotency {
            if (lockTimeout == null || lockTimeout.compareTo(Duration.ofMillis(1)) < 0
                    || lockTimeout.compareTo(Duration.ofSeconds(2)) > 0) {
                throw new IllegalArgumentException("horus.bpm.processes.idempotency.lock-timeout must be between 1 "
                        + "millisecond and 2 seconds, was " + lockTimeout);
            }
        }
    }

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
     * Business calendars (#72; PD-118 as amended by PD-131).
     *
     * @param approval {@code four-eyes} (the default): a calendar is created or changed only when a second holder of
     *     {@code processes:calendar:manage} approves the proposal; {@code single}: the proposer applies it alone,
     *     accepted only when the {@code dev} profile is active on its own, and refused at start-up otherwise
     */
    public record Calendar(@DefaultValue("four-eyes") CalendarApproval approval) {
    }

    /** Who applies a calendar change: a second person, or, in the {@code dev} profile only, the proposer. */
    public enum CalendarApproval {
        /** A second holder of {@code processes:calendar:manage} approves; the default. */
        FOUR_EYES,
        /** The proposer applies it alone; {@code dev} profile only. */
        SINGLE
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
        kinds.put(io.github.dhluong90.bpm.process.internal.store.JobStore.COMPENSATION,
                runners.compensation().toOverrides());
        return new io.github.dhluong90.bpm.process.internal.service.AsyncSettings(jobs.leaseMargin(),
                adapters.defaultTimeout(), adapters.executorThreads(), roles, kinds);
    }

    /**
     * Agent step settings (#59, #101, DESIGN section 4.1).
     *
     * @param labels bounds of the labels an agent reports with its answer
     * @param memory bounds of the memory an agent step receives from earlier steps
     */
    public record Agents(@DefaultValue Labels labels, @DefaultValue Memory memory) {
    }

    /**
     * Bounds of the memory an agent step receives from earlier agent steps of the same instance
     * ({@code <horus:memory>}, #101). They may be lowered, never raised.
     *
     * @param maxEntries memory entries per agent step, 0 to 16, checked at import; 0 refuses every step that declares
     *     memory
     * @param maxBytes bytes of the resolved memory as canonical JSON, 1 to 16384, checked when the step is reached; a
     *     larger memory fails the step with {@code AGENT_MEMORY_TOO_LARGE} before any call
     */
    public record Memory(@DefaultValue("16") int maxEntries, @DefaultValue("16384") int maxBytes) {

        public Memory {
            try {
                new io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits(maxEntries, maxBytes);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage()
                        + " (it may be lowered, never raised)", e);
            }
        }

        /** @return the validated bounds */
        io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits toLimits() {
            return new io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits(maxEntries, maxBytes);
        }
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
     * @param compensation the runner of the undos of a compensation walk (#67): its own retry policy, timeout and
     *     slots, so undos are never queued behind forward steps
     */
    public record Runners(@DefaultValue Runner asyncService, @DefaultValue Runner asyncAgent,
            @DefaultValue Runner compensation) {
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
     * @param maxRoles most role and group queues one task list reads; a caller with more gets
     *     {@code 400 ROLE_FAN_OUT_EXCEEDED} unless the request names one {@code role} or {@code group} (#408)
     * @param statementTimeout longest one statement of a list read may run, set with {@code SET LOCAL} in a read-only
     *     transaction of its own (ADR-0015); a read that exceeds it is answered {@code 503 QUERY_TIMEOUT}, never with
     *     a partial page. Positive; two seconds by default (#254)
     * @param dueSoonWindow a task due within this time reads {@code slaState: DUE_SOON} in the task list (PD-111,
     *     #472); positive, one hour by default
     */
    public record Query(@DefaultValue("200") int maxPageSize, @DefaultValue("P31D") Duration maxRange,
            @DefaultValue("100") int maxRoles, @DefaultValue("PT1H") Duration dueSoonWindow,
            @DefaultValue("PT2S") Duration statementTimeout) {
        public Query {
            if (statementTimeout.isNegative() || statementTimeout.isZero()) {
                throw new IllegalArgumentException("horus.bpm.processes.query.statement-timeout must be positive");
            }
            if (dueSoonWindow.isNegative() || dueSoonWindow.isZero()) {
                throw new IllegalArgumentException("horus.bpm.processes.query.due-soon-window must be positive");
            }
            if (maxRoles < 1 || maxRoles > EngineDefaults.MAX_ROLES_LIMIT) {
                throw new IllegalArgumentException("horus.bpm.processes.query.max-roles must be 1 to "
                        + EngineDefaults.MAX_ROLES_LIMIT);
            }
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
     * @param rollupEnabled whether this replica runs the hourly node statistics rollup (PRE-029)
     * @param rollupInterval time between rollup runs
     * @param rollupLag how far behind now the rollup stays, so slow step transactions have committed
     * @param rollupMaxSlice longest stretch of history one slice transaction reads
     * @param rollupMaxRun longest a run keeps taking slices before the next run continues from the watermark
     * @param rollupInitialBackfill how far back the first run starts on a database with no watermark
     * @param rollupMinLag the write-lag window: the longest a step transaction may stay open before its history row
     *     commits. No slice is rolled up within it and {@code rollup-lag} may not be shorter; a value below PT5M is
     *     accepted on the {@code dev} profile only (#410)
     * @param rollupRetention how long hourly statistics rows are kept, as an ISO-8601 period such as P13M or P90D, at
     *     least P31D; older whole days are deleted by the rollup run (#410)
     */
    public record Stats(
            @DefaultValue("PT5S") Duration liveCacheTtl,
            @DefaultValue("PT15S") Duration liveCacheTtlLarge,
            @DefaultValue("100000") long liveCacheLargeThreshold,
            @DefaultValue("true") boolean rollupEnabled,
            @DefaultValue("PT5M") Duration rollupInterval,
            @DefaultValue("PT5M") Duration rollupLag,
            @DefaultValue("PT1M") Duration rollupMaxSlice,
            @DefaultValue("PT60S") Duration rollupMaxRun,
            @DefaultValue("PT1H") Duration rollupInitialBackfill,
            @DefaultValue("PT5M") Duration rollupMinLag,
            @DefaultValue("P13M") java.time.Period rollupRetention) {

        public Stats {
            if (liveCacheTtl.isNegative() || liveCacheTtlLarge.isNegative()) {
                throw new IllegalArgumentException("horus.bpm.processes.stats.live-cache-ttl and live-cache-ttl-large "
                        + "must not be negative");
            }
            if (liveCacheLargeThreshold < 1) {
                throw new IllegalArgumentException("horus.bpm.processes.stats.live-cache-large-threshold must be positive");
            }
            try {
                toRollupSettings(rollupEnabled, rollupInterval, rollupLag, rollupMaxSlice, rollupMaxRun,
                        rollupInitialBackfill, rollupMinLag, rollupRetention);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage(), e);
            }
        }

        private static io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings toRollupSettings(boolean enabled,
                Duration interval, Duration lag, Duration maxSlice, Duration maxRun, Duration backfill,
                Duration minLag, java.time.Period retention) {
            return new io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings(enabled, interval, lag,
                    maxSlice, maxRun, backfill, minLag, retention);
        }

        LiveStatsSettings toSettings() {
            return new LiveStatsSettings(liveCacheTtl, liveCacheTtlLarge, liveCacheLargeThreshold);
        }

        io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings toRollup() {
            return toRollupSettings(rollupEnabled, rollupInterval, rollupLag, rollupMaxSlice, rollupMaxRun,
                    rollupInitialBackfill, rollupMinLag, rollupRetention);
        }
    }

    /**
     * Operational metrics on the host's {@code MeterRegistry} (PRE-084). Tags are engine values only; no tag ever
     * holds a tenant, an instance id, a business key, a user or a variable.
     *
     * @param enabled whether the metrics are bound when Micrometer is present
     * @param gaugeRefresh how often each replica reads the gauges ({@code instances.active}, {@code incidents.open},
     *     {@code jobs.due.oldest.age}) with one bounded query set; never per scrape; 1 second to 1 hour
     * @param definitionKeyTag whether the counters of instances carry the {@code definitionKey} tag; off by default
     *     because the number of keys is the host's and is not bounded by the engine
     */
    public record Metrics(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT30S") Duration gaugeRefresh,
            @DefaultValue("false") boolean definitionKeyTag) {

        public Metrics {
            if (gaugeRefresh.compareTo(Duration.ofSeconds(1)) < 0 || gaugeRefresh.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("horus.bpm.processes.metrics.gauge-refresh must be 1 second to 1 hour");
            }
        }
    }

    /**
     * Offline replay of recorded agent answers against a candidate guardrail or output schema (#98, PRE-092 to
     * PRE-095).
     *
     * @param pageSize recorded answers replayed per worker transaction, 1 to 1,000
     * @param maxCases most recorded answers one run may cover; may be lowered, never raised above 50,000
     * @param retention how long a run and its report are kept before they are deleted, 1 hour to 366 days
     */
    public record Replay(
            @DefaultValue("500") int pageSize,
            @DefaultValue("50000") int maxCases,
            @DefaultValue("P30D") Duration retention) {

        public Replay {
            try {
                new ReplaySettings(pageSize, maxCases, retention);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage(), e);
            }
        }

        ReplaySettings toSettings() {
            return new ReplaySettings(pageSize, maxCases, retention);
        }
    }

    /**
     * A step that calls a child process (#92, PRE-212).
     *
     * @param maxDepth most calls one above another: a child started at a deeper level raises the incident
     *     {@code CALL_DEPTH_EXCEEDED} and no child starts; 1 to 5, may be lowered, never raised
     */
    public record Calls(@DefaultValue("5") int maxDepth) {

        public Calls {
            if (maxDepth < 1 || maxDepth > EngineDefaults.MAX_CALL_DEPTH) {
                throw new IllegalArgumentException("horus.bpm.processes.calls.max-depth must be 1 to "
                        + EngineDefaults.MAX_CALL_DEPTH + ", was " + maxDepth);
            }
        }
    }

    /**
     * Loops and multi-instance steps (#496).
     *
     * @param max most repetitions of one looped activity: a collection, a cardinality or a {@code loopMaximum}
     *     over it opens the incident {@code MULTI_INSTANCE_LIMIT} and creates no task; 1 to 1,000, default 1,000
     */
    public record MultiInstance(@DefaultValue("1000") int max) {

        public MultiInstance {
            if (max < 1 || max > EngineDefaults.MAX_LOOP_INSTANCES) {
                throw new IllegalArgumentException("horus.bpm.processes.multi-instance.max must be 1 to "
                        + EngineDefaults.MAX_LOOP_INSTANCES + ", was " + max);
            }
        }
    }

    /**
     * Migration plans of running instances (#74, PD-114, PD-131).
     *
     * @param approval the approval a new plan records for its execution: {@code four-eyes} (the default), or
     *     {@code single}, which start-up accepts only when the {@code dev} profile is active on its own
     * @param dryRunSampleSize most instance ids a dry run lists per conflict and for the instances that would move,
     *     1 to 500; may be lowered, never raised
     * @param dryRunValidity how long an execution may rely on a dry run of the same plan, at most {@code PT1H}; may be
     *     lowered only
     * @param batchSize instances the migration runner reads at a time, 1 to 100; may be lowered only
     * @param lease how long a runner holds a plan without renewing it, {@code PT10S} to {@code PT10M}; a crashed
     *     runner's plan is taken over after this time
     * @param lockWait how long the runner waits for an instance row another transaction holds when it opens the incident
     *     of a failed instance, {@code PT1S} to {@code PT30S}
     * @param cancelApproval who approves the cancellation of a removed step's work (#106, PRE-210): {@code single} (the
     *     default): the holder of {@code processes:migration:manage} who proposes the plan is the only one needed for the
     *     cancellations, as the single-approver retirement of PD-102; {@code four-eyes} is reserved and refused at start-up
     *     until it is built
     */
    public record Migration(
            @DefaultValue("four-eyes") String approval,
            @DefaultValue("100") int dryRunSampleSize,
            @DefaultValue("PT1H") Duration dryRunValidity,
            @DefaultValue("100") int batchSize,
            @DefaultValue("PT2M") Duration lease,
            @DefaultValue("PT5S") Duration lockWait,
            @DefaultValue("single") String cancelApproval) {

        public Migration {
            if (!"single".equals(cancelApproval)) {
                throw new IllegalArgumentException("horus.bpm.processes.migration.cancel-approval must be single, was "
                        + cancelApproval + "; four-eyes cancellation is reserved and not built yet");
            }
            try {
                toSettings(approval, dryRunSampleSize, dryRunValidity, batchSize, lease, lockWait);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("horus.bpm.processes." + e.getMessage(), e);
            }
        }

        MigrationSettings toSettings() {
            return toSettings(approval, dryRunSampleSize, dryRunValidity, batchSize, lease, lockWait);
        }

        private static MigrationSettings toSettings(String approval, int sampleSize, Duration validity, int batchSize,
                Duration lease, Duration lockWait) {
            return new MigrationSettings(io.github.dhluong90.bpm.process.api.MigrationPlan.Approval.of(approval),
                    sampleSize, validity, batchSize, lease, lockWait);
        }
    }

    /**
     * @param requireContentHash an approval of a definition version, a migration plan or a calendar change must name
     *     {@code expectedDefinitionHash}, {@code expectedPlanHash} or {@code expectedChangeHash}, the hash of the
     *     content the approver reviewed (PD-139, PRE-177, AUD-02); {@code false} is accepted on the {@code dev}
     *     profile only
     */
    public record Approval(@DefaultValue("true") boolean requireContentHash) {
    }

    /**
     * @param resolveApproval {@code four-eyes} (the default): resolving an incident with an operator's output is a
     *     proposal that a second holder of {@code processes:incident:manage}, never the proposer, approves or
     *     rejects (PD-126, PRE-078); {@code single} applies the proposer's resolution at once and is accepted on the
     *     {@code dev} profile only
     */
    public record Incidents(@DefaultValue("four-eyes") String resolveApproval) {

        public Incidents {
            if (!"four-eyes".equals(resolveApproval) && !"single".equals(resolveApproval)) {
                throw new IllegalArgumentException("horus.bpm.processes.incidents.resolve-approval must be four-eyes "
                        + "or single, was " + resolveApproval);
            }
        }

        EngineSettings.ResolveApproval toSetting() {
            return "single".equals(resolveApproval) ? EngineSettings.ResolveApproval.SINGLE
                    : EngineSettings.ResolveApproval.FOUR_EYES;
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
                lifecycle.reasonMaxLength(), makerChecker.enabled(), approverRule, retireLastVersion,
                approval.requireContentHash(), incidents.toSetting(),
                classification == null || classification.warnUnclassified(), query.maxRoles(),
                query.dueSoonWindow());
    }
}

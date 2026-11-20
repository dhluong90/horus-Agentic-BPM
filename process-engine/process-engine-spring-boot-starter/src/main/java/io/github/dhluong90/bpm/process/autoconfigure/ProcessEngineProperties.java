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
 * @param query read limits of the list endpoints
 * @param lifecycle instance lifecycle settings
 * @param stats live count settings
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
        @DefaultValue Query query,
        @DefaultValue Lifecycle lifecycle,
        @DefaultValue Stats stats) {

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
     */
    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String replicaId,
            @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("32") int batchSize,
            @DefaultValue("60s") Duration timerLease,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("10s") Duration retryBackoff) {

        public Jobs {
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
     * @param reasonMaxLength longest reason a cancellation accepts (PD-103); may be lowered, never raised
     */
    public record Lifecycle(@DefaultValue("2000") int reasonMaxLength) {
        public Lifecycle {
            if (reasonMaxLength < 1 || reasonMaxLength > EngineDefaults.MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("horus.bpm.processes.lifecycle.reason-max-length must be 1 to "
                        + EngineDefaults.MAX_REASON_LENGTH + " (it may be lowered, never raised)");
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

    EngineSettings toSettings() {
        return new EngineSettings(definitions.autoApprove(), definitions.maxNodes(), definitions.maxBytes(),
                idempotencyRetention, handlers.slowCallWarning(), query.maxPageSize(), query.maxRange(),
                lifecycle.reasonMaxLength());
    }
}

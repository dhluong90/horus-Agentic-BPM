package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validated settings of Async Wait-State steps (ADR-0008, DESIGN sections 4 and 6.5), bound by the starter from
 * {@code horus.bpm.processes.jobs.lease-margin} and {@code horus.bpm.processes.adapters.*}.
 *
 * @param leaseMargin {@code jobs.lease-margin}: added to the step timeout to give the lease of an async job, so a live
 *     attempt, which the engine abandons at the timeout, reaches its result transaction before the lease ends
 * @param defaultTimeout {@code adapters.default-timeout}: the timeout of a remote step that declares none
 * @param executorThreads {@code adapters.executor-threads}: adapter calls in flight per replica; a job is claimed only
 *     when one of these slots is free
 * @param completerRoles {@code adapters.completer-roles}: per adapter name, the roles whose holders may complete its
 *     steps through the completion API, on top of {@code processes:step:complete}. An adapter without an entry has
 *     no completer: nobody can complete its steps from outside. Role names as the caller resolver returns them; a
 *     {@code ROLE_} prefix is removed
 */
public record AsyncSettings(Duration leaseMargin, Duration defaultTimeout, int executorThreads,
        Map<String, Set<String>> completerRoles) {

    /** Default {@code jobs.lease-margin}. */
    public static final Duration DEFAULT_LEASE_MARGIN = Duration.ofSeconds(30);
    /** Default {@code adapters.default-timeout}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** Default {@code adapters.executor-threads}. */
    public static final int DEFAULT_EXECUTOR_THREADS = 16;
    /** The defaults. */
    public static final AsyncSettings DEFAULTS = new AsyncSettings(DEFAULT_LEASE_MARGIN, DEFAULT_TIMEOUT,
            DEFAULT_EXECUTOR_THREADS);

    /**
     * Settings with no completer roles: no adapter's steps can be completed from outside.
     *
     * @param leaseMargin the lease margin
     * @param defaultTimeout the default timeout
     * @param executorThreads the adapter slots
     */
    public AsyncSettings(Duration leaseMargin, Duration defaultTimeout, int executorThreads) {
        this(leaseMargin, defaultTimeout, executorThreads, Map.of());
    }

    public AsyncSettings {
        Objects.requireNonNull(leaseMargin, "leaseMargin must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        if (leaseMargin.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("jobs.lease-margin must be at least 1 second");
        }
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("adapters.default-timeout must be positive");
        }
        if (executorThreads < 1 || executorThreads > 1_000) {
            throw new IllegalArgumentException("adapters.executor-threads must be 1 to 1000");
        }
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        (completerRoles == null ? Map.<String, Set<String>>of() : completerRoles).forEach((adapter, names) -> {
            if (adapter == null || adapter.isBlank()) {
                throw new IllegalArgumentException("adapters.completer-roles has an entry without an adapter name");
            }
            roles.put(adapter, (names == null ? Set.<String>of() : names).stream()
                    .filter(n -> n != null && !n.isBlank())
                    .map(n -> n.startsWith("ROLE_") ? n.substring(5) : n)
                    .collect(Collectors.toUnmodifiableSet()));
        });
        completerRoles = Map.copyOf(roles);
    }

    /**
     * @param adapter an adapter name
     * @param callerRoles the caller's roles
     * @return whether the caller holds one of the roles configured for the adapter
     */
    public boolean mayComplete(String adapter, Set<String> callerRoles) {
        Set<String> allowed = completerRoles.getOrDefault(adapter, Set.of());
        return callerRoles != null && callerRoles.stream().anyMatch(allowed::contains);
    }

    /**
     * The lease of an async job: the step timeout plus the margin (PRE-017).
     *
     * @param stepTimeout the step's own timeout, or null for {@link #defaultTimeout()}
     * @return the lease
     */
    public Duration lease(Duration stepTimeout) {
        return timeout(stepTimeout).plus(leaseMargin);
    }

    /**
     * @param stepTimeout the step's own timeout, or null
     * @return the timeout that applies
     */
    public Duration timeout(Duration stepTimeout) {
        return stepTimeout == null ? defaultTimeout : stepTimeout;
    }
}

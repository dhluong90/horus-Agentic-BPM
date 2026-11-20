package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The compiled versions one replica serves, keyed by tenant and rule set, bounded by {@code cache.max-entries}
 * with least-recently-used eviction (ADR-0009, RLE-034).
 *
 * <p>An entry holds the version in force over a window {@code [validFrom, nextEffectiveFrom)} and the rule set's
 * {@code cache_epoch} it was read with. The window is bounded by the nearest instants, before and after the
 * evaluation instant, at which any approved version of the rule set starts or was retired, so the selection of
 * DESIGN 3.3.1 cannot change inside it. Inside the window an evaluation is served without reading the
 * database; at {@code nextEffectiveFrom} a later published version takes over and the entry is reloaded, with no
 * epoch change. A publication increments the epoch and notifies every replica after commit; {@link #evict} drops
 * the entry on the notification, and {@link #checkEpochs()} catches what a connection pooler or a lost listener
 * connection drops, every {@code cache.epoch-check-interval}. An entry read with an epoch older than one already
 * notified is never installed, so a load that raced a publication cannot outlive it.
 *
 * <p>Thread-safe. Lookups and installs take one short lock; compilation and database reads happen outside it.
 */
public final class CompiledCache {

    /** Why an entry left the cache; the {@code cause} tag of {@code horus.rle.cache.evictions}. */
    public enum Cause {
        /** The cache was full and this entry was the least recently used. */
        SIZE,
        /** The epoch check found the rule set's {@code cache_epoch} moved. */
        EPOCH,
        /** A publication notification, from this replica after commit or from another one. */
        NOTIFY
    }

    private final int maxEntries;
    private final RuleStore store;
    private final TransactionRunner transactions;
    private final Map<Key, Entry> entries;
    private final Map<Key, Long> notified;
    private final Map<Cause, AtomicLong> evictions = new EnumMap<>(Cause.class);

    CompiledCache(int maxEntries, RuleStore store, TransactionRunner transactions) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, was " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        for (Cause cause : Cause.values()) {
            evictions.put(cause, new AtomicLong());
        }
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Key, CompiledCache.Entry> eldest) {
                if (size() > CompiledCache.this.maxEntries) {
                    evictions.get(Cause.SIZE).incrementAndGet();
                    return true;
                }
                return false;
            }
        };
        // Highest epoch notified per rule set, kept for as many rule sets as the cache holds.
        this.notified = new LinkedHashMap<>(16, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Key, Long> eldest) {
                return size() > CompiledCache.this.maxEntries;
            }
        };
    }

    /**
     * Returns the compiled version effective at an instant, or null when the cache cannot answer for it.
     *
     * @param tenant the tenant
     * @param ruleSet the rule set name
     * @param at the evaluation instant
     * @return the entry, or null
     */
    synchronized Entry get(String tenant, String ruleSet, Instant at) {
        Key key = new Key(tenant, ruleSet);
        Entry entry = entries.get(key);
        return entry != null && entry.covers(at) ? entry : null;
    }

    /**
     * Installs a freshly compiled entry, unless a publication newer than its epoch was notified meanwhile.
     *
     * @param entry the entry
     */
    synchronized void put(Entry entry) {
        Key key = new Key(entry.version().tenantId(), entry.version().ruleSet());
        Long newest = notified.get(key);
        if (newest != null && newest > entry.epoch()) {
            return;
        }
        Entry current = entries.get(key);
        if (current != null && current.epoch() > entry.epoch()) {
            return;
        }
        entries.put(key, entry);
    }

    /**
     * Drops the entry of a rule set when it was read before {@code epoch}, and remembers the epoch so an older load
     * in flight is not installed. Called on a publication notification and after this replica's own approval
     * commits.
     *
     * @param tenant the tenant
     * @param ruleSet the rule set name
     * @param epoch the rule set's new {@code cache_epoch}
     * @return true when an entry was dropped
     */
    public synchronized boolean evict(String tenant, String ruleSet, long epoch) {
        Key key = new Key(tenant, ruleSet);
        notified.merge(key, epoch, Math::max);
        Entry entry = entries.get(key);
        if (entry != null && entry.epoch() < epoch) {
            entries.remove(key);
            evictions.get(Cause.NOTIFY).incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Compares every cached entry with its rule set's {@code cache_epoch} and drops the entries whose epoch moved or
     * whose rule set is gone: one indexed read per cached tenant, in a read-only transaction each. The staleness
     * bound of ADR-0009; runs every {@code cache.epoch-check-interval} on the scheduler thread.
     *
     * @return the number of entries dropped
     */
    public int checkEpochs() {
        Map<String, Map<String, Long>> byTenant = new HashMap<>();
        synchronized (this) {
            for (java.util.Map.Entry<Key, CompiledCache.Entry> e : entries.entrySet()) {
                byTenant.computeIfAbsent(e.getKey().tenant(), t -> new HashMap<>())
                        .put(e.getKey().ruleSet(), e.getValue().epoch());
            }
        }
        int dropped = 0;
        for (java.util.Map.Entry<String, Map<String, Long>> tenant : byTenant.entrySet()) {
            Map<String, Long> current = transactions.inReadOnlyTransaction(
                    () -> store.cacheEpochs(tenant.getKey(), List.copyOf(tenant.getValue().keySet())));
            List<String> stale = new ArrayList<>();
            tenant.getValue().forEach((ruleSet, epoch) -> {
                Long now = current.get(ruleSet);
                if (now == null || now > epoch) {
                    stale.add(ruleSet);
                }
            });
            synchronized (this) {
                for (String ruleSet : stale) {
                    Key key = new Key(tenant.getKey(), ruleSet);
                    Entry entry = entries.get(key);
                    Long now = current.get(ruleSet);
                    if (entry != null && (now == null || now > entry.epoch())) {
                        entries.remove(key);
                        evictions.get(Cause.EPOCH).incrementAndGet();
                        dropped++;
                    }
                }
            }
        }
        return dropped;
    }

    /**
     * Returns the number of compiled entries held; the {@code horus.rle.cache.size} gauge.
     *
     * @return at most {@link #maxEntries()}
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Returns how many entries left the cache for a cause; the {@code horus.rle.cache.evictions} counter.
     *
     * @param cause the cause
     * @return the count since start-up
     */
    public long evictions(Cause cause) {
        return evictions.get(cause).get();
    }

    /**
     * Returns the bound, {@code cache.max-entries}.
     *
     * @return the most entries held at once
     */
    public int maxEntries() {
        return maxEntries;
    }

    private record Key(String tenant, String ruleSet) {
    }

    /**
     * One compiled version and the window in which it is the effective one.
     *
     * @param version the stored version (its canonical document already checked against its hash)
     * @param compiled the compiled rules
     * @param epoch the rule set's {@code cache_epoch} the version was read with
     * @param validFrom the start of the window: the latest instant at or before the evaluation instant at which an
     *     approved version started or was retired
     * @param nextEffectiveFrom the end of the window, exclusive: the next instant at which an approved version starts
     *     or is retired, or null
     */
    record Entry(StoredVersion version, CompiledRuleSet compiled, long epoch, Instant validFrom,
            Instant nextEffectiveFrom) {

        Entry {
            Objects.requireNonNull(validFrom, "validFrom must not be null");
        }

        boolean covers(Instant at) {
            return !at.isBefore(validFrom) && (nextEffectiveFrom == null || at.isBefore(nextEffectiveFrom));
        }
    }
}

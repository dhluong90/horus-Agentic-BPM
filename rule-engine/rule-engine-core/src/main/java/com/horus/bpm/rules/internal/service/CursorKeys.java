package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.QueryInvalidException;
import com.horus.bpm.rules.api.SchemaNotReadyException;
import com.horus.bpm.rules.internal.store.RuleStore;
import com.horus.bpm.rules.internal.store.TransactionRunner;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The key that signs list cursors, held in memory (RLE-039). It is created or read once at start-up, after the
 * migrations, by {@link #load()}, and re-read off the request path by {@link #refresh()}. A request only reads
 * memory: signing and verifying a cursor cost one HMAC and never a database call, a connection or a write.
 *
 * <p>The holder keeps the current key and, after a change, the previous one, so cursors issued just before a
 * rotation still verify for one refresh period. Rotation itself is not offered in this release.
 */
public final class CursorKeys {

    /** Purpose of the row in {@code rle_engine_key}. */
    public static final String NAME = "cursor-hmac";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RuleStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private volatile Pair keys;
    private volatile String notReady;

    private record Pair(Cursors current, byte[] currentKey, Cursors previous) {
    }

    CursorKeys(RuleStore store, TransactionRunner transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Creates the key if none exists and reads it, in one short transaction. Call once at start-up, after the
     * migrations and outside any request. Replicas that start together all read the same row.
     *
     * @throws IllegalStateException naming the table and the rights needed when the key cannot be created or read
     */
    public void load() {
        byte[] candidate = new byte[32];
        RANDOM.nextBytes(candidate);
        byte[] key;
        try {
            key = transactions.inTransaction(() -> store.engineKey(NAME, candidate, clock.instant()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The Rule Engine could not create or read its cursor key in rle_engine_key; "
                    + "the database user needs SELECT and INSERT on rle_engine_key", e);
        }
        install(key);
    }

    /**
     * Re-reads the key in a read-only transaction and swaps it in when it changed. Runs on the starter's scheduler
     * thread, never on a request. A missing row keeps the key in memory.
     */
    public void refresh() {
        Optional<byte[]> key = transactions.inReadOnlyTransaction(() -> store.findEngineKey(NAME));
        key.ifPresent(this::install);
    }

    /**
     * Tells whether a key is held.
     *
     * @return whether {@link #load()} has run
     */
    public boolean loaded() {
        return keys != null;
    }

    /**
     * Records why the key was not loaded at start-up (the schema state), so list calls answer
     * {@code 503 SCHEMA_NOT_READY} naming it instead of an internal error.
     *
     * @param schemaState for example "schema at version 4, 1 migration pending, migrate-on-start=false"
     */
    public void notReady(String schemaState) {
        this.notReady = schemaState;
    }

    synchronized void install(byte[] key) {
        Pair now = keys;
        if (now != null && Arrays.equals(now.currentKey(), key)) {
            return;
        }
        keys = new Pair(new Cursors(key), key.clone(), now == null ? null : now.current());
    }

    String sign(String tenant, String scope, String list, String lastKey) {
        return held().current().sign(tenant, scope, list, lastKey);
    }

    String verify(String tenant, String scope, String list, String cursor) {
        Pair pair = held();
        try {
            return pair.current().verify(tenant, scope, list, cursor);
        } catch (QueryInvalidException e) {
            if (pair.previous() == null || cursor == null) {
                throw e;
            }
            return pair.previous().verify(tenant, scope, list, cursor);
        }
    }

    private Pair held() {
        Pair pair = keys;
        if (pair == null) {
            String state = notReady;
            throw new SchemaNotReadyException("List cursors are not available: the Rule Engine cursor key was not "
                    + "loaded at start-up" + (state == null ? "" : " (" + state + ")")
                    + ". Run the engine migrations and restart.");
        }
        return pair;
    }
}

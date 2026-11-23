package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.DataStoreAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The demo's BPMN data stores, one {@link DataStoreAdapter} each: {@code credit-limits} is read-only reference data,
 * {@code score-ledger} keeps what a step writes, and {@code ledger-notes} does both. The contents are in memory and
 * lost on restart; a real host reads and writes its own tables here, in the step's transaction. Every key starts with
 * the tenant of the instance, so one tenant never sees another's entries, and a write repeated under the same
 * idempotency key stores the same value once.
 */
@Configuration(proxyBeanMethods = false)
class DemoDataStores {

    /** The limit every demo tenant reads from {@code credit-limits}, in minor units of VND. */
    static final Map<String, Object> DEFAULT_LIMIT = Map.of("maxAmountMinor", 500_000_000L, "currency", "VND");

    @Bean
    DataStoreAdapter creditLimitsStore() {
        return new DataStoreAdapter() {
            @Override
            public String name() {
                return "credit-limits";
            }

            @Override
            public Object read(DataStoreAccess access) {
                return DEFAULT_LIMIT;
            }
        };
    }

    @Bean
    DataStoreAdapter scoreLedgerStore() {
        return new MemoryStore("score-ledger");
    }

    @Bean
    DataStoreAdapter ledgerNotesStore() {
        return new MemoryStore("ledger-notes");
    }

    /** A store that keeps the last value written per tenant, store, instance and item. */
    static final class MemoryStore implements DataStoreAdapter {

        private final String name;
        private final Map<List<String>, Object> values = new ConcurrentHashMap<>();

        MemoryStore(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Object read(DataStoreAccess access) {
            Object value = values.get(key(access));
            if (value == null) {
                throw new IllegalStateException("Nothing is stored in " + name + " for " + access.item());
            }
            return value;
        }

        @Override
        public void write(DataStoreAccess access, Object value) {
            values.put(key(access), value);
        }

        private static List<String> key(DataStoreAccess access) {
            return List.of(access.tenantId(), access.instanceId(), access.item());
        }
    }
}

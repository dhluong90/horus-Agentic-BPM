package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.CompiledCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;
import java.util.Objects;

/**
 * Binds the compiled-cache meters of DESIGN section 4.8 to the host's meter registry: gauge
 * {@code horus.rle.cache.size} (Prometheus {@code horus_rle_cache_size}) and counter
 * {@code horus.rle.cache.evictions} (Prometheus {@code horus_rle_cache_evictions_total}) with tag {@code cause}
 * ({@code size}, {@code epoch}, {@code notify}). No tag carries a tenant or a rule set.
 */
final class RuleEngineCacheMetrics implements MeterBinder {

    static final String SIZE = "horus.rle.cache.size";
    static final String EVICTIONS = "horus.rle.cache.evictions";

    private final CompiledCache cache;

    RuleEngineCacheMetrics(CompiledCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(SIZE, cache, CompiledCache::size)
                .description("Compiled rule set versions held by this replica; at most cache.max-entries")
                .register(registry);
        for (CompiledCache.Cause cause : CompiledCache.Cause.values()) {
            FunctionCounter.builder(EVICTIONS, cache, c -> c.evictions(cause))
                    .description("Compiled entries that left the cache, by cause")
                    .tag("cause", cause.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }
}

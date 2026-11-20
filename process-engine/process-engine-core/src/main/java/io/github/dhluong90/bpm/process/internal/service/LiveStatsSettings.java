package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the live count cache, bound from {@code horus.bpm.processes.stats.*} by the starter.
 *
 * @param liveCacheTtl {@code stats.live-cache-ttl}: how long a replica answers from its cached counts
 * @param liveCacheTtlLarge {@code stats.live-cache-ttl-large}: the TTL when the last count exceeded the threshold
 * @param liveCacheLargeThreshold {@code stats.live-cache-large-threshold}: live tokens above which the large TTL applies
 */
public record LiveStatsSettings(Duration liveCacheTtl, Duration liveCacheTtlLarge, long liveCacheLargeThreshold) {

    /** Default {@code stats.live-cache-ttl}. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);
    /** Default {@code stats.live-cache-ttl-large}. */
    public static final Duration DEFAULT_TTL_LARGE = Duration.ofSeconds(15);
    /** Default {@code stats.live-cache-large-threshold}. */
    public static final long DEFAULT_LARGE_THRESHOLD = 100_000;

    public LiveStatsSettings {
        Objects.requireNonNull(liveCacheTtl, "liveCacheTtl");
        Objects.requireNonNull(liveCacheTtlLarge, "liveCacheTtlLarge");
        if (liveCacheTtl.isNegative() || liveCacheTtlLarge.isNegative()) {
            throw new IllegalArgumentException("stats.live-cache-ttl and stats.live-cache-ttl-large must not be negative");
        }
        if (liveCacheLargeThreshold < 1) {
            throw new IllegalArgumentException("stats.live-cache-large-threshold must be positive");
        }
    }

    /** @return the engine defaults */
    public static LiveStatsSettings defaults() {
        return new LiveStatsSettings(DEFAULT_TTL, DEFAULT_TTL_LARGE, DEFAULT_LARGE_THRESHOLD);
    }
}

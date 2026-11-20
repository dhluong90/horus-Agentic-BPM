package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:async}: run as an Async Wait-State (ADR-0008).
 *
 * @param async async
 * @param timeout timeout
 */
public record AsyncMode(
        boolean async,
        Duration timeout) implements HorusExtension {
}

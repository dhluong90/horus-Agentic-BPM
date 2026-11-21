package io.github.dhluong90.bpm.process.spi;

/**
 * The correlation and causation ids that the events of the current thread carry (DESIGN section 6.4). A host or an
 * adapter opens a scope around an engine call, for example with the {@code X-Correlation-Id} of the request and its
 * {@code Idempotency-Key}; every event the engine hands to the {@link ProcessEventPublisher} inside that call has
 * them in its envelope. Without a scope the correlation chain starts at the event itself and there is no recorded
 * cause. Scopes nest and restore the outer one on close.
 */
public final class EventScope implements AutoCloseable {

    private record Ids(String correlationId, String causationId) {
    }

    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private final Ids outer;

    private EventScope(Ids outer) {
        this.outer = outer;
    }

    /**
     * Opens a scope on this thread; close it in a {@code finally} or try-with-resources.
     *
     * @param correlationId the correlation id, or null to leave each chain to start at its first event
     * @param causationId the id of the command or job the work belongs to, or null
     * @return the scope
     */
    public static EventScope open(String correlationId, String causationId) {
        EventScope scope = new EventScope(CURRENT.get());
        CURRENT.set(new Ids(correlationId, causationId));
        return scope;
    }

    /** @return the correlation id of the open scope, or null */
    public static String correlationId() {
        Ids ids = CURRENT.get();
        return ids == null ? null : ids.correlationId();
    }

    /** @return the causation id of the open scope, or null */
    public static String causationId() {
        Ids ids = CURRENT.get();
        return ids == null ? null : ids.causationId();
    }

    @Override
    public void close() {
        if (outer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(outer);
        }
    }
}

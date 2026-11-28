package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import java.util.Objects;

/**
 * The engine's port for tracing (#114, TR-OBS-03): one span per evaluation call, started before the call so work
 * inside it is a child, ended with ids, versions, codes and outcomes only. The default does nothing and allocates
 * nothing; the starter binds a tracer when the host has one. A value handed to the hook is never an input, an output
 * or a message of an exception (TR-SEC-05). Implementations must not throw; one that does is ignored.
 */
public final class EngineTracing {

    /** Starts a span for one evaluation call. */
    public interface Hook {
        /** @return the span; its {@link Span#end} is called exactly once */
        Span start();
    }

    /** One evaluation span. */
    public interface Span {

        /** Does nothing. */
        Span NONE = new Span() {
        };

        /**
         * The call returned a result.
         *
         * @param tenant the tenant of the call, or null when it cannot be resolved
         * @param result the result; read its ids and outcome only, never its inputs or outputs
         */
        default void ended(String tenant, DecisionResult result) {
        }

        /**
         * The call threw.
         *
         * @param tenant the tenant of the call, or null
         * @param errorCode an engine error code, or null for an unexpected exception
         * @param failure the exception; use its class only, never its message
         */
        default void failed(String tenant, String errorCode, Throwable failure) {
        }
    }

    private volatile Hook hook;

    /**
     * Binds the tracer.
     *
     * @param hook the hook
     */
    public void bind(Hook hook) {
        this.hook = Objects.requireNonNull(hook, "hook must not be null");
    }

    Span start() {
        Hook bound = hook;
        if (bound == null) {
            return Span.NONE;
        }
        try {
            Span span = bound.start();
            return span == null ? Span.NONE : span;
        } catch (RuntimeException ignored) {
            return Span.NONE;
        }
    }
}

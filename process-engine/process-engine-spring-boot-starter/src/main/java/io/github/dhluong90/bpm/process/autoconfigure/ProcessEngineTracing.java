package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.micrometer.tracing.Tracer;
import java.util.Objects;

/**
 * Binds the engine's trace points to the host's Micrometer {@link Tracer} (#114, TR-OBS-03): each step the kernel
 * applies is one span {@code horus.process.step}, a child of the request or job that ran it, with the definition key
 * and version, the step kind and the outcome. A business key, an instance id, a variable or the message of an
 * exception never reaches a span (TR-SEC-05). The tenant is an attribute only when
 * {@code horus.bpm.tracing.tenant-attribute} is true (default).
 */
final class ProcessEngineTracing implements ProcessTracing {

    static final String SPAN = "horus.process.step";

    private final Tracer tracer;
    private final boolean tenantAttribute;

    ProcessEngineTracing(Tracer tracer, boolean tenantAttribute) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.tenantAttribute = tenantAttribute;
    }

    @Override
    public ProcessTracing.Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind) {
        io.micrometer.tracing.Span span = tracer.nextSpan().name(SPAN).start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        span.tag("horus.definition_key", definitionKey);
        span.tag("horus.definition_version", Integer.toString(definitionVersion));
        span.tag("horus.step_kind", stepKind);
        if (tenantAttribute && tenantId != null) {
            span.tag("horus.tenant_id", tenantId);
        }
        return (outcome, errorCode, failure) -> {
            try {
                span.tag("horus.outcome", outcome);
                if (errorCode != null) {
                    span.tag("horus.error_code", errorCode);
                }
                if (failure != null) {
                    span.tag("horus.error_type", failure.getClass().getSimpleName());
                    span.error(new TracedFailure(errorCode));
                }
            } finally {
                try {
                    scope.close();
                } finally {
                    span.end();
                }
            }
        };
    }

    /** What a span records as its error: a code, never the message or stack of the real exception. */
    static final class TracedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TracedFailure(String code) {
            super(code == null ? "error" : code, null, false, false);
        }
    }
}

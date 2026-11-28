package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the engine's trace points to the host's Micrometer {@link Tracer} (#114, TR-OBS-03): each step the kernel
 * applies is one span {@code horus.process.step}, a child of the request or job that ran it, with the definition key
 * and version, the step kind and the outcome. A business key, an instance id, a variable or the message of an
 * exception never reaches a span (TR-SEC-05). The tenant is an attribute only when
 * {@code horus.bpm.tracing.tenant-attribute} is true (default).
 */
final class ProcessEngineTracing implements ProcessTracing {

    static final String SPAN = "horus.process.step";

    /** A trace id (16 or 32 hex digits) and a span id (16), as {@link #linkOf} writes them. */
    private static final Pattern REFERENCE = Pattern.compile("([0-9a-f]{16,32})-([0-9a-f]{16})");

    private final Tracer tracer;
    private final boolean tenantAttribute;

    ProcessEngineTracing(Tracer tracer, boolean tenantAttribute) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.tenantAttribute = tenantAttribute;
    }

    @Override
    public ProcessTracing.Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind) {
        return begin(tracer.nextSpan().name(SPAN).start(), tenantId, definitionKey, definitionVersion, stepKind);
    }

    /**
     * The result of an async call: a span in the current trace, if there is one, with a link to the span that
     * scheduled the call. A reference that does not parse, or a tracer that cannot link, gives the plain span: the
     * link is never a reason to fail a step.
     */
    @Override
    public ProcessTracing.Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind,
            String linkedFrom) {
        io.micrometer.tracing.Span span = null;
        try {
            TraceContext origin = parse(linkedFrom);
            if (origin != null) {
                span = tracer.spanBuilder().name(SPAN).addLink(new Link(origin)).start();
            }
        } catch (RuntimeException | LinkageError e) {
            span = null;
        }
        return begin(span != null ? span : tracer.nextSpan().name(SPAN).start(), tenantId, definitionKey,
                definitionVersion, stepKind);
    }

    /** Makes the started span current and tags it; the scope is closed and the span ended on any Throwable. */
    private ProcessTracing.Span begin(io.micrometer.tracing.Span span, String tenantId, String definitionKey,
            int definitionVersion, String stepKind) {
        Tracer.SpanInScope scope;
        try {
            scope = tracer.withSpan(span);
        } catch (Throwable t) {
            span.end();
            throw t;
        }
        try {
            span.tag("horus.definition_key", definitionKey);
            span.tag("horus.definition_version", Integer.toString(definitionVersion));
            span.tag("horus.step_kind", stepKind);
            if (tenantAttribute && tenantId != null) {
                span.tag("horus.tenant_id", tenantId);
            }
        } catch (Throwable t) {
            close(scope, span);
            throw t;
        }
        return new StepSpan(span, scope, linkOf(span));
    }

    /** {@code traceId-spanId}, lower-case hex; null for a span without ids, such as a no-op span. */
    private static String linkOf(io.micrometer.tracing.Span span) {
        try {
            TraceContext context = span.context();
            String trace = context.traceId();
            String id = context.spanId();
            return trace == null || id == null || trace.isEmpty() || id.isEmpty() ? null : trace + "-" + id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The context a link points at, or null when the reference is not {@code traceId-spanId} in hex. */
    private TraceContext parse(String reference) {
        if (reference == null) {
            return null;
        }
        Matcher m = REFERENCE.matcher(reference);
        if (!m.matches()) {
            return null;
        }
        return tracer.traceContextBuilder().traceId(m.group(1)).spanId(m.group(2)).sampled(true).build();
    }

    /** Closes the scope and ends the span, whatever either throws, so no span stays current on the thread. */
    private static void close(Tracer.SpanInScope scope, io.micrometer.tracing.Span span) {
        try {
            scope.close();
        } finally {
            span.end();
        }
    }

    /** A step span in progress: ends the Micrometer span and its scope once, whatever the tags throw. */
    private static final class StepSpan implements ProcessTracing.Span {
        private final io.micrometer.tracing.Span span;
        private final Tracer.SpanInScope scope;
        private final String link;

        StepSpan(io.micrometer.tracing.Span span, Tracer.SpanInScope scope, String link) {
            this.span = span;
            this.scope = scope;
            this.link = link;
        }

        @Override
        public void end(String outcome, String errorCode, Throwable failure) {
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
                close(scope, span);
            }
        }

        @Override
        public String link() {
            return link;
        }
    }

    /** What a span records as its error: a code, never the message or stack of the real exception. */
    static final class TracedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TracedFailure(String code) {
            super(code == null ? "error" : code, null, false, false);
        }
    }
}

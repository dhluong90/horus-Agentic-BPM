package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.internal.service.EngineTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Objects;

/**
 * Binds the engine's tracing port to the host's Micrometer {@link Tracer} (#114, TR-OBS-03): every evaluation is one
 * span {@code horus.rule.evaluate}, a child of whatever span is current, with the rule set, its version, the decision
 * id, the outcome and the number of matched rules. An input, an output or an exception message never reaches a span
 * (TR-SEC-05). The tenant is an attribute only when {@code horus.bpm.tracing.tenant-attribute} is true (default).
 */
final class RuleEngineTracing implements EngineTracing.Hook {

    static final String SPAN = "horus.rule.evaluate";

    private final Tracer tracer;
    private final boolean tenantAttribute;

    RuleEngineTracing(Tracer tracer, boolean tenantAttribute) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.tenantAttribute = tenantAttribute;
    }

    @Override
    public EngineTracing.Span start() {
        Span span = tracer.nextSpan().name(SPAN).start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return new EngineTracing.Span() {
            @Override
            public void ended(String tenant, DecisionResult result) {
                try {
                    tenant(span, tenant);
                    span.tag("horus.rule_set", result.ruleSet());
                    span.tag("horus.rule_set.version", Integer.toString(result.version()));
                    span.tag("horus.decision_id", result.decisionId());
                    switch (result.outcome()) {
                        case DecisionResult.Matched matched -> {
                            span.tag("horus.outcome", "MATCHED");
                            span.tag("horus.matched_count", Integer.toString(matched.ruleIds().size()));
                        }
                        case DecisionResult.NoMatch ignored -> span.tag("horus.outcome", "NO_MATCH");
                        case DecisionResult.Failed failed -> {
                            span.tag("horus.outcome", "FAILED");
                            span.tag("horus.error_code", failed.code());
                            span.error(new TracedFailure(failed.code()));
                        }
                    }
                } finally {
                    close(span, scope);
                }
            }

            @Override
            public void failed(String tenant, String errorCode, Throwable failure) {
                try {
                    tenant(span, tenant);
                    span.tag("horus.outcome", "REFUSED");
                    if (errorCode != null) {
                        span.tag("horus.error_code", errorCode);
                    }
                    span.tag("horus.error_type", failure.getClass().getSimpleName());
                    span.error(new TracedFailure(errorCode));
                } finally {
                    close(span, scope);
                }
            }
        };
    }

    private void tenant(Span span, String tenant) {
        if (tenantAttribute && tenant != null) {
            span.tag("horus.tenant_id", tenant);
        }
    }

    private static void close(Span span, Tracer.SpanInScope scope) {
        try {
            scope.close();
        } finally {
            span.end();
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

package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.internal.service.EngineTracing;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The evaluation span of RLE-228 on an in-memory tracer: ids and outcome only, never an input or an output. */
class RuleEngineTracingTest {

    private final SimpleTracer tracer = new SimpleTracer();

    @Test
    void RLE_228_a_matched_evaluation_is_one_span_with_rule_set_version_decision_and_count_but_no_value() {
        // Given a matched decision whose output holds a customer-specific value
        EngineTracing.Span span = new RuleEngineTracing(tracer, true).start();
        DecisionResult result = new DecisionResult("dec-01", "card-fee", 3, Instant.parse("2026-11-28T10:00:00Z"),
                new DecisionResult.Matched(List.of("R2"), Map.of("fee", "079090001234")));

        // When the call ends
        span.ended("bank-a", result);

        // Then the span holds the bounded attributes and none of the output
        SimpleSpan recorded = only(tracer);
        assertThat(recorded.getName()).isEqualTo("horus.rule.evaluate");
        assertThat(recorded.getTags()).containsOnly(Map.entry("horus.rule_set", "card-fee"),
                Map.entry("horus.rule_set.version", "3"), Map.entry("horus.decision_id", "dec-01"),
                Map.entry("horus.outcome", "MATCHED"), Map.entry("horus.matched_count", "1"),
                Map.entry("horus.tenant_id", "bank-a"));
        assertThat(recorded.getEndTimestamp()).isNotNull();
    }

    @Test
    void RLE_228_a_refused_call_records_an_error_code_and_the_exception_type_never_its_message() {
        // Given the tenant attribute is off
        EngineTracing.Span span = new RuleEngineTracing(tracer, false).start();

        // When the call throws with a message that holds an input value
        span.failed("bank-a", "INPUT_INVALID", new IllegalArgumentException("txCount 079090001234 is not a number"));

        // Then the span is REFUSED with the code and type, with no tenant and no message
        SimpleSpan recorded = only(tracer);
        assertThat(recorded.getTags()).doesNotContainKey("horus.tenant_id")
                .containsEntry("horus.outcome", "REFUSED").containsEntry("horus.error_code", "INPUT_INVALID")
                .containsEntry("horus.error_type", "IllegalArgumentException");
        assertThat(recorded.getTags().values()).noneMatch(v -> v.contains("079090001234"));
        assertThat(recorded.getError()).hasMessage("INPUT_INVALID").hasNoCause();
    }

    private static SimpleSpan only(SimpleTracer tracer) {
        assertThat(tracer.getSpans()).hasSize(1);
        return tracer.getSpans().getFirst();
    }
}

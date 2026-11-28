package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

/** The step span of PRE-518 on an in-memory tracer: bounded attributes, no message, tenant on request. */
class ProcessEngineTracingTest {

    private final SimpleTracer tracer = new SimpleTracer();

    @Test
    void PRE_518_a_step_is_one_span_with_the_definition_the_kind_and_the_outcome() {
        // Given the binding with the tenant attribute on
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);

        // When a step starts and ends OK
        tracing.startStep("bank-a", "loan-disbursement", 3, "START").end("OK", null, null);

        // Then one span carries ids, version, kind, outcome and tenant, and is ended
        SimpleSpan span = only(tracer);
        assertThat(span.getName()).isEqualTo("horus.process.step");
        assertThat(span.getTags()).containsOnly(java.util.Map.entry("horus.definition_key", "loan-disbursement"),
                java.util.Map.entry("horus.definition_version", "3"), java.util.Map.entry("horus.step_kind", "START"),
                java.util.Map.entry("horus.outcome", "OK"), java.util.Map.entry("horus.tenant_id", "bank-a"));
        assertThat(span.getEndTimestamp()).isNotNull();
    }

    @Test
    void PRE_518_the_tenant_attribute_can_be_turned_off_and_a_failure_records_its_code_and_type_never_its_message() {
        // Given the binding with the tenant attribute off
        ProcessTracing tracing = new ProcessEngineTracing(tracer, false);

        // When a step fails with a message that holds a business key
        tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT").end("FAILED", "ADAPTER_TIMEOUT",
                new IllegalStateException("customer 079090001234 not found"));

        // Then no tenant, a code and a type, and the recorded error carries the code only
        SimpleSpan span = only(tracer);
        assertThat(span.getTags()).doesNotContainKey("horus.tenant_id")
                .containsEntry("horus.error_code", "ADAPTER_TIMEOUT")
                .containsEntry("horus.error_type", "IllegalStateException");
        assertThat(span.getTags().values()).noneMatch(v -> v.contains("079090001234"));
        assertThat(span.getError()).hasMessage("ADAPTER_TIMEOUT").hasNoCause();
    }

    @Test
    void PRE_518_a_step_that_ends_on_an_Error_leaves_no_current_span_for_the_next_tenant() {
        // Given a step of one tenant that fails with an Error, not an Exception
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);
        tracing.startStep("bank-a", "loan-disbursement", 3, "START").end("FAILED", null,
                new StackOverflowError());
        assertThat(tracer.currentSpan()).isNull();

        // When the next unit of work, of another tenant, starts on the same thread
        tracing.startStep("bank-b", "loan-disbursement", 3, "START").end("OK", null, null);

        // Then its span has no parent from the earlier span
        assertThat(tracer.getSpans()).hasSize(2);
        assertThat(tracer.getSpans().getLast().context().parentId()).isNullOrEmpty();
        assertThat(tracer.getSpans()).allSatisfy(span -> assertThat(span.getEndTimestamp()).isNotNull());
    }

    private static SimpleSpan only(SimpleTracer tracer) {
        assertThat(tracer.getSpans()).hasSize(1);
        return tracer.getSpans().getFirst();
    }
}

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

    @Test
    void PRE_518_a_step_names_itself_by_trace_id_and_span_id_only_so_a_later_span_can_link_to_it() {
        // Given a step span that ended
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);
        ProcessTracing.Span scheduling = tracing.startStep("bank-a", "loan-disbursement", 3, "START");
        scheduling.end("OK", null, null);

        // Then its link is the trace id and the span id of that span, and nothing else, also after it ended
        SimpleSpan span = only(tracer);
        assertThat(scheduling.link()).isEqualTo(span.context().traceId() + "-" + span.context().spanId())
                .matches("[0-9a-f]{16,32}-[0-9a-f]{16}");
    }

    @Test
    void PRE_518_the_result_of_an_async_call_is_a_span_linked_to_the_span_that_scheduled_it() {
        // Given the scheduling step, in one trace, and its stored link
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);
        ProcessTracing.Span scheduling = tracing.startStep("bank-a", "loan-disbursement", 3, "START");
        scheduling.end("OK", null, null);
        String stored = scheduling.link();
        SimpleSpan scheduled = tracer.getSpans().getFirst();

        // When the result is applied later, on another thread of work with no current span
        tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT", stored).end("OK", null, null);

        // Then the result span has one link, to the scheduling span, and the same bounded attributes as any step
        assertThat(tracer.getSpans()).hasSize(2);
        SimpleSpan result = tracer.getSpans().getLast();
        assertThat(result.getName()).isEqualTo("horus.process.step");
        assertThat(result.getLinks()).hasSize(1);
        assertThat(result.getLinks().getFirst().getTraceContext().traceId()).isEqualTo(scheduled.context().traceId());
        assertThat(result.getLinks().getFirst().getTraceContext().spanId()).isEqualTo(scheduled.context().spanId());
        assertThat(result.getLinks().getFirst().getTags()).isEmpty();
        assertThat(result.getTags()).containsEntry("horus.step_kind", "ASYNC_RESULT").containsEntry("horus.outcome", "OK")
                .doesNotContainKey("horus.link");
        assertThat(result.getEndTimestamp()).isNotNull();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void PRE_518_a_link_that_is_not_two_hex_ids_gives_a_plain_span_and_never_fails_the_step() {
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);

        // When the stored link is damaged, or carries something that is not an id
        tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT", "not a link").end("OK", null, null);
        tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT", "079088001234-NATIONAL").end("OK", null, null);

        // Then both steps ran as ordinary spans, with no link and no copy of the text
        assertThat(tracer.getSpans()).hasSize(2).allSatisfy(span -> {
            assertThat(span.getLinks()).isEmpty();
            assertThat(span.getTags().values()).noneMatch(v -> v.contains("079088001234") || v.contains("not a link"));
            assertThat(span.getEndTimestamp()).isNotNull();
        });
    }

    @Test
    void PRE_518_a_linked_step_that_ends_on_an_Error_leaves_no_current_span() {
        ProcessTracing tracing = new ProcessEngineTracing(tracer, true);
        ProcessTracing.Span scheduling = tracing.startStep("bank-a", "loan-disbursement", 3, "START");
        scheduling.end("OK", null, null);

        tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT", scheduling.link()).end("FAILED", null,
                new StackOverflowError());

        assertThat(tracer.currentSpan()).isNull();
        assertThat(tracer.getSpans()).allSatisfy(span -> assertThat(span.getEndTimestamp()).isNotNull());
    }

    private static SimpleSpan only(SimpleTracer tracer) {
        assertThat(tracer.getSpans()).hasSize(1);
        return tracer.getSpans().getFirst();
    }
}

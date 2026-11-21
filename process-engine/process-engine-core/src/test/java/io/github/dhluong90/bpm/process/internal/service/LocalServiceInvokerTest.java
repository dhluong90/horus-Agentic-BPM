package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A local handler of a loan disbursement, {@code limits.reserve}, called by the invoker (PRE-071): its time is read
 * from an injected ticker, so a slow call is proven without sleeping, and a handler that throws propagates without its
 * message.
 */
class LocalServiceInvokerTest {

    private static final Duration WARNING = Duration.ofMillis(200);
    private static final Map<String, Object> INPUTS = Map.of("customerId", "C-0042", "nationalId", "079088001234",
            "amount", 50_000_000L);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(LocalServiceInvoker.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void capture() {
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void release() {
        logger.removeHandler(capture);
    }

    private LocalServiceInvoker invoker(Function<ServiceCall, HandlerResult> body) {
        ServiceTaskHandler handler = new ServiceTaskHandler() {
            @Override
            public String name() {
                return "limits.reserve";
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                return body.apply(call);
            }
        };
        return new LocalServiceInvoker(Map.of(handler.name(), handler), Optional.empty(), WARNING, nanos::get);
    }

    private static Effect.InvokeService reserve() {
        return new Effect.InvokeService("token-1", "reserve-limit", "01JQ0ATTEMPT000000000000001", ServiceKind.HANDLER,
                "limits.reserve", INPUTS, ExecutionMode.SYNC_LOCAL);
    }

    private StepResult invoke(LocalServiceInvoker invoker) {
        return invoker.invoke("bank-a", "01JQ0INSTANCE00000000000001", "LN-2026-0042", reserve(), null,
                Instant.parse("2026-11-20T04:00:00Z"));
    }

    private List<String> messages() {
        return records.stream().map(r -> java.text.MessageFormat.format(r.getMessage(), r.getParameters())).toList();
    }

    @Test
    void PRE_071_a_local_call_above_the_slow_call_warning_logs_the_step_and_elapsed_time_never_the_inputs() {
        // Given limits.reserve takes 250 ms on the ticker, above handlers.slow-call-warning (200 ms)
        LocalServiceInvoker invoker = invoker(call -> {
            nanos.addAndGet(Duration.ofMillis(250).toNanos());
            return HandlerResult.completed(Map.of("reservationId", "RSV-0042"));
        });

        // When the step runs
        StepResult result = invoke(invoker);

        // Then it completed, and one process.step.slow line names the step, the handler and 250 ms, without inputs
        assertThat(result).isEqualTo(new StepResult.Completed(Map.of("reservationId", "RSV-0042")));
        assertThat(messages()).filteredOn(m -> m.startsWith("process.step.slow")).singleElement().satisfies(m -> {
            assertThat(m).contains("target=limits.reserve").contains("node=reserve-limit").contains("elapsedMs=250")
                    .contains("thresholdMs=200");
            assertThat(m).doesNotContain("C-0042").doesNotContain("079088001234").doesNotContain("50000000")
                    .doesNotContain("50,000,000");
        });
    }

    @Test
    void PRE_071_a_local_call_at_the_threshold_logs_nothing() {
        LocalServiceInvoker invoker = invoker(call -> {
            nanos.addAndGet(WARNING.toNanos());
            return HandlerResult.completed(Map.of());
        });

        invoke(invoker);

        assertThat(messages()).noneMatch(m -> m.startsWith("process.step.slow"));
    }

    @Test
    void PRE_071_a_handler_that_throws_propagates_its_exception_without_its_message() {
        // Given limits.reserve throws with a message that carries the customer's national id
        IllegalStateException thrown = new IllegalStateException("limit row locked for 079088001234");
        LocalServiceInvoker invoker = invoker(call -> {
            throw thrown;
        });

        // Then the call throws, the cause is the handler's exception, and neither the message nor the log has its text
        assertThatThrownBy(() -> invoke(invoker)).isInstanceOfSatisfying(LocalHandlerException.class, e -> {
            assertThat(e.getCause()).isSameAs(thrown);
            assertThat(e.handler()).isEqualTo("limits.reserve");
            assertThat(e.nodeId()).isEqualTo("reserve-limit");
            assertThat(e.getMessage()).contains("java.lang.IllegalStateException").doesNotContain("079088001234");
        });
        assertThat(messages()).anySatisfy(m -> assertThat(m).startsWith("process.handler.error")
                .contains("target=limits.reserve")).allSatisfy(m -> assertThat(m).doesNotContain("079088001234"));
    }

    @Test
    void PRE_070_a_local_handler_receives_tenant_instance_step_business_key_attempt_id_and_only_its_inputs() {
        List<ServiceCall> calls = new CopyOnWriteArrayList<>();
        LocalServiceInvoker invoker = invoker(call -> {
            calls.add(call);
            return HandlerResult.completed(Map.of());
        });

        invoke(invoker);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.tenantId()).isEqualTo("bank-a");
            assertThat(call.instanceId()).isEqualTo("01JQ0INSTANCE00000000000001");
            assertThat(call.nodeId()).isEqualTo("reserve-limit");
            assertThat(call.businessKey()).isEqualTo("LN-2026-0042");
            assertThat(call.attemptId()).isEqualTo("01JQ0ATTEMPT000000000000001");
            assertThat(call.inputs()).isEqualTo(INPUTS);
        });
    }
}

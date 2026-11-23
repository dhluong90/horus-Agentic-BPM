package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.SignalBroadcast;
import io.github.dhluong90.bpm.process.api.SignalService;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The signal endpoint's answers as the controller and the problem handler write them, validated against
 * {@code process-engine/api/openapi.yaml}. The service is a double: it refuses a signal that starts a process whose
 * message catch has no key, as the engine does.
 */
class SignalRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String PATH = ProcessController.BASE + "/signals";

    private final SignalService signals = request -> {
        if (request.name().equals("consent-signed")) {
            throw new StepFailedException("-", "CORRELATION_KEY_REQUIRED",
                    "A signal start gives the instance no business key to correlate under");
        }
        return new SignalBroadcast("01JD7Q3V8K2M5N9P0R4S6T8WXY", request.name(), 0, List.of());
    };

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SignalController(signals))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void a_signal_start_that_cannot_correlate_is_422_STEP_FAILED_with_the_reason_not_a_bare_500() throws Exception {
        MvcResult refused = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k-1").content("{\"name\":\"consent-signed\"}")).andReturn();

        BODIES.assertExchange(refused);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("\"code\":\"STEP_FAILED\"")
                .contains("\"reason\":\"CORRELATION_KEY_REQUIRED\"");
    }
}

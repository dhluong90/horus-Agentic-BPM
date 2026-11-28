package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.DeadLetter;
import io.github.dhluong90.bpm.rules.api.DeadLetterAction;
import io.github.dhluong90.bpm.rules.api.DeadLetterState;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionSearchResult;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/** RLE-235: the dead-letter endpoints pass the caller's request to the service and answer in the contract's shapes. */
class DeadLetterEndpointsWebMvcTest {

    static final String ID = "01KBX9Q3M4R7T2V8W6Y0Z1A2B3";
    static final DeadLetter DEAD = new DeadLetter(ID, "card-fee", 1, Instant.parse("2027-02-01T09:00:00Z"), 5,
            "new row violates check constraint \"rle_decision_outcome_check\" [23514]", DeadLetterState.DEAD, null, null,
            null);

    Set<String> permissions = Set.of("rules:log:read", "rules:log:operate", "rules:log:approve");
    final List<String> calls = new ArrayList<>();
    RuntimeException thrown;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "olivia";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        DecisionLogService service = new DecisionLogService() {
            @Override
            public LoggedDecision get(String decisionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DecisionSearchResult findByMaskedInput(MaskedInputQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DeadLetter> deadLetters(int limit) {
                calls.add("list " + limit);
                return List.of(DEAD);
            }

            @Override
            public DeadLetter requestDeadLetterAction(String decisionId, DeadLetterAction action, String comment,
                    String idempotencyKey) {
                calls.add("request " + decisionId + " " + action + " '" + comment + "' key=" + idempotencyKey);
                return new DeadLetter(ID, "card-fee", 1, DEAD.queuedAt(), 5, DEAD.lastError(),
                        DeadLetterState.RETRY_REQUESTED, "olivia", comment, Instant.parse("2027-02-01T10:00:00Z"));
            }

            @Override
            public String decideDeadLetterRequest(String decisionId, boolean approve, String comment,
                    String idempotencyKey) {
                calls.add((approve ? "approve " : "reject ") + decisionId + " '" + comment + "' key=" + idempotencyKey);
                if (thrown != null) {
                    throw thrown;
                }
                return approve ? "RETRY_APPROVED" : "REQUEST_REJECTED";
            }
        };
        DecisionLogQuery query = (DecisionLogQuery) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DecisionLogQuery.class}, (proxy, method, args) -> null);
        mvc = MockMvcBuilders.standaloneSetup(new DecisionLogController(service, query, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    JsonNode body(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsByteArray());
    }

    @Test
    void RLE_235_the_list_shows_each_dead_letter_with_its_attempts_and_last_error() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/decision-log/dead-letters").param("limit", "20"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode first = body(response).path("data").get(0);
        assertThat(first.path("decisionId").asString()).isEqualTo(ID);
        assertThat(first.path("attempts").asInt()).isEqualTo(5);
        assertThat(first.path("state").asString()).isEqualTo("DEAD");
        assertThat(first.path("lastError").asString()).contains("rle_decision_outcome_check");
        assertThat(first.has("requestedBy")).isFalse();
        assertThat(calls).containsExactly("list 20");
    }

    @Test
    void RLE_235_a_request_carries_its_action_reason_and_idempotency_key_and_answers_the_waiting_state() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:request")
                .header("Idempotency-Key", "key-7").contentType("application/json")
                .content("{\"action\":\"RETRY\",\"comment\":\"February partition created\"}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).path("state").asString()).isEqualTo("RETRY_REQUESTED");
        assertThat(body(response).path("requestedBy").asString()).isEqualTo("olivia");
        assertThat(calls).containsExactly("request " + ID + " RETRY 'February partition created' key=key-7");
    }

    @Test
    void RLE_235_an_unknown_action_or_member_is_refused_before_the_service_is_called() throws Exception {
        MockHttpServletResponse unknownAction = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:request")
                .contentType("application/json").content("{\"action\":\"DELETE\",\"comment\":\"remove it\"}"))
                .andReturn().getResponse();
        MockHttpServletResponse unknownMember = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:approve")
                .contentType("application/json").content("{\"tenantId\":\"bank-b\"}")).andReturn().getResponse();

        assertThat(unknownAction.getStatus()).isEqualTo(400);
        assertThat(body(unknownAction).path("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(unknownMember.getStatus()).isEqualTo(400);
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_235_the_requester_approving_their_own_request_is_403_self_approval_forbidden() throws Exception {
        thrown = SelfApprovalForbiddenException.deadLetter(ID);

        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:approve")
                .contentType("application/json").content("{}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void RLE_235_approve_and_reject_answer_the_event_recorded_and_each_needs_its_own_permission() throws Exception {
        MockHttpServletResponse approved = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:approve")
                .header("Idempotency-Key", "k1").contentType("application/json").content("{\"comment\":\"checked\"}"))
                .andReturn().getResponse();
        MockHttpServletResponse rejected = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:reject")
                .contentType("application/json").content("{\"comment\":\"not the cause\"}")).andReturn().getResponse();

        assertThat(body(approved).path("event").asString()).isEqualTo("RETRY_APPROVED");
        assertThat(body(rejected).path("event").asString()).isEqualTo("REQUEST_REJECTED");
        assertThat(calls).containsExactly("approve " + ID + " 'checked' key=k1", "reject " + ID + " 'not the cause' key=null");

        permissions = Set.of("rules:log:read", "rules:log:operate");
        MockHttpServletResponse denied = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:approve")
                .contentType("application/json").content("{}")).andReturn().getResponse();
        permissions = Set.of("rules:log:read");
        MockHttpServletResponse deniedRequest = mvc.perform(post("/api/bpm/rules/v1/decisions/" + ID + "/dead-letter:request")
                .contentType("application/json").content("{\"action\":\"RETRY\",\"comment\":\"try again\"}"))
                .andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(deniedRequest.getStatus()).isEqualTo(403);
        assertThat(calls).hasSize(2);
    }
}

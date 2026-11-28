package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.ContentChangedException;
import io.github.dhluong90.bpm.process.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.IncidentNotFoundException;
import io.github.dhluong90.bpm.process.api.IncidentQuery;
import io.github.dhluong90.bpm.process.api.IncidentService;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.OutputInvalidException;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PendingResolution;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The incident endpoints (#68) and the content-bound approval problems (#406), as the controller and the problem
 * handler write them, validate against {@code process-engine/api/openapi.yaml}. The incident service is a test
 * double that answers like the engine; the controller, the JSON views and the problem details are the real ones.
 */
class IncidentRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String BASE = ProcessController.BASE;
    private static final Instant NOW = Instant.parse("2027-01-04T09:00:00Z");
    private static final String INC = "01JD0000000000000000000I01";

    private final FakeIncidents incidents = new FakeIncidents();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new IncidentController(incidents))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_522_the_definition_key_and_version_filters_reach_the_service_and_a_bad_version_is_refused() throws Exception {
        // When the list is read with a key and a version, with a key only, and with neither
        assertThat(exchange(get(BASE + "/incidents").param("definitionKey", "loan-disbursement").param("version", "2"))
                .getResponse().getStatus()).isEqualTo(200);
        exchange(get(BASE + "/incidents").param("definitionKey", "loan-disbursement"));
        exchange(get(BASE + "/incidents"));

        // Then the service got each filter, and none when none was sent
        assertThat(incidents.queries).extracting(IncidentQuery::definitionKey)
                .containsExactly("loan-disbursement", "loan-disbursement", null);
        assertThat(incidents.queries).extracting(IncidentQuery::version).containsExactly(2, null, null);
        // And a version that is no number is a QUERY_INVALID problem
        assertProblem(get(BASE + "/incidents").param("definitionKey", "loan-disbursement").param("version", "two"),
                400, "QUERY_INVALID");
    }

    @Test
    void PRE_218_the_incident_list_has_an_etag_that_follows_its_content_and_the_callers_actions() throws Exception {
        // When the list is read, it carries an ETag
        MvcResult first = exchange(get(BASE + "/incidents"));
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");

        // Then the same tag is 304 with no body, and the same list reads the same tag
        MvcResult unchanged = exchange(get(BASE + "/incidents").header("If-None-Match", etag));
        assertThat(unchanged.getResponse().getStatus()).isEqualTo(304);
        assertThat(unchanged.getResponse().getContentAsString()).isEmpty();

        // And when an incident changes, or the caller's own actions change, the tag changes and the body is sent
        incidents.listLock = 1;
        MvcResult changed = exchange(get(BASE + "/incidents").header("If-None-Match", etag));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        incidents.listLock = 0;
        incidents.listActions = List.of();
        MvcResult otherView = exchange(get(BASE + "/incidents").header("If-None-Match", etag));
        assertThat(otherView.getResponse().getStatus()).isEqualTo(200);
        assertThat(otherView.getResponse().getHeader("ETag")).isNotEqualTo(etag);
    }

    @Test
    void PRE_080_the_list_and_one_incident_match_the_contract_with_the_callers_actions() throws Exception {
        MvcResult list = exchange(get(BASE + "/incidents").param("status", "OPEN").param("limit", "50"));

        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains("\"errorCode\":\"ADAPTER_TIMEOUT\"")
                .contains("\"allowedActions\":[\"retry\",\"resolve\"]").contains("\"hasNext\":false");
        assertThat(incidents.queries).extracting(IncidentQuery::status).containsExactly("OPEN");

        MvcResult one = exchange(get(BASE + "/incidents/" + INC));
        assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
        assertProblem(get(BASE + "/incidents/01JD0000000000000000000I09"), 404, "INCIDENT_NOT_FOUND");
    }

    @Test
    void PRE_077_a_retry_answers_202_with_the_incident_still_open() throws Exception {
        MvcResult retried = exchange(post(BASE + "/incidents/" + INC + ":retry").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"").header("Idempotency-Key", "r-1")
                .content("{\"reason\":\"Core banking back after maintenance\"}"));

        assertThat(retried.getResponse().getStatus()).isEqualTo(202);
        assertThat(retried.getResponse().getContentAsString()).contains("\"status\":\"OPEN\"")
                .contains("\"retryCount\":1").contains("\"retrying\":true");
        assertThat(retried.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    }

    @Test
    void PRE_078_a_proposal_waits_for_a_second_person_and_its_approval_resolves_the_incident() throws Exception {
        MvcResult proposed = exchange(post(BASE + "/incidents/" + INC + ":resolve").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"").header("Idempotency-Key", "p-1").content("""
                        {"output":{"approvedLimit":{"amount":"30000000","currency":"VND"}},
                         "reason":"Limit raised by credit committee, memo CC-118"}"""));
        assertThat(proposed.getResponse().getContentAsString()).contains("\"status\":\"RESOLUTION_PENDING\"")
                .contains("\"proposedBy\":\"ops.lan\"").contains("\"approvalMode\":\"four-eyes\"");
        assertThat(incidents.outputs).singleElement().satisfies(o -> assertThat(o).containsKey("approvedLimit"));

        MvcResult approved = exchange(post(BASE + "/incidents/" + INC + ":approve-resolution")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"1\"").header("Idempotency-Key", "a-1")
                .content("{\"comment\":\"Checked against memo CC-118\"}"));
        assertThat(approved.getResponse().getContentAsString()).contains("\"status\":\"RESOLVED\"")
                .contains("\"resolution\":\"COMPLETED_MANUALLY\"").contains("\"resolvedBy\":\"ops.minh\"");

        MvcResult rejected = exchange(post(BASE + "/incidents/" + INC + ":reject-resolution")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"1\"").header("Idempotency-Key", "j-1")
                .content("{\"reason\":\"Memo CC-118 approves 25,000,000 VND\"}"));
        assertThat(rejected.getResponse().getContentAsString()).contains("\"status\":\"OPEN\"");
    }

    @Test
    void PRE_078_TR_SEC_08_every_incident_problem_matches_the_contract_and_names_no_value() throws Exception {
        // 428: no If-Match, then no Idempotency-Key.
        assertProblem(post(BASE + "/incidents/" + INC + ":retry").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "r-1").content("{\"reason\":\"x\"}"), 428, "PRECONDITION_REQUIRED");
        assertProblem(post(BASE + "/incidents/" + INC + ":retry").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"").content("{\"reason\":\"x\"}"), 428, "PRECONDITION_REQUIRED");
        // 422: the output breaks the step's schema; the path is named, the value is not.
        MvcResult invalid = assertProblem(post(BASE + "/incidents/" + INC + ":resolve")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"").header("Idempotency-Key", "p-2")
                .content("{\"output\":{\"approvedLimit\":\"lots\"},\"reason\":\"guess\"}"), 422, "OUTPUT_INVALID");
        assertThat(invalid.getResponse().getContentAsString()).contains("\"paths\":[\"$.approvedLimit: expected object\"]")
                .doesNotContain("lots");
        // 403: the proposer approves; 403: only processes:read.
        assertProblem(post(BASE + "/incidents/" + INC + ":approve-resolution").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"1\"").header("Idempotency-Key", "a-self").content("{}"), 403,
                "SELF_APPROVAL_FORBIDDEN");
        assertProblem(post(BASE + "/incidents/" + INC + ":reject-resolution").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"1\"").header("Idempotency-Key", "j-read")
                .content("{\"reason\":\"no\"}"), 403, "PERMISSION_DENIED");
        // 409: a retry of a resolved incident.
        assertProblem(post(BASE + "/incidents/" + INC + ":retry").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"9\"").header("Idempotency-Key", "r-9").content("{\"reason\":\"again\"}"), 409,
                "INVALID_STATE");
    }

    @Test
    void PRE_177_the_two_approval_refusals_match_the_contract_and_name_the_current_content() throws Exception {
        MockMvc definitions = MockMvcBuilders.standaloneSetup(new ProcessController(refusingApprovals(),
                proxy(io.github.dhluong90.bpm.process.api.ProcessEngine.class),
                proxy(io.github.dhluong90.bpm.process.api.TaskService.class),
                proxy(io.github.dhluong90.bpm.process.api.ProcessQueryService.class), 1_000_000))
                .setControllerAdvice(new ProcessProblemDetails()).build();
        MvcResult missing = definitions.perform(post(BASE + "/definitions/kyc-onboarding/versions/2:approve")
                .header("If-Match", "\"1\"")).andReturn();
        BODIES.assertExchange(missing);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString()).contains("\"code\":\"CONTENT_HASH_REQUIRED\"");

        MvcResult changed = definitions.perform(post(BASE + "/definitions/kyc-onboarding/versions/3:approve")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"1\"")
                .content("{\"expectedDefinitionHash\":\"" + "a".repeat(64) + "\"}")).andReturn();
        BODIES.assertExchange(changed);
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(changed.getResponse().getContentAsString()).contains("\"code\":\"CONTENT_CHANGED\"")
                .contains("\"currentDefinitionHash\":\"" + "b".repeat(64) + "\"").contains("\"currentLockVersion\":3");
    }

    /** A definition service whose approval refuses as the engine does: no hash, or a hash of other content. */
    private static io.github.dhluong90.bpm.process.api.DefinitionService refusingApprovals() {
        return (io.github.dhluong90.bpm.process.api.DefinitionService) java.lang.reflect.Proxy.newProxyInstance(
                IncidentRestContractTest.class.getClassLoader(),
                new Class<?>[] {io.github.dhluong90.bpm.process.api.DefinitionService.class}, (self, method, args) -> {
                    if (method.getName().equals("approve") && args.length == 6) {
                        if (args[2] == null) {
                            throw new ContentHashRequiredException();
                        }
                        throw new ContentChangedException("changed since it was reviewed", "b".repeat(64), 3);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(IncidentRestContractTest.class.getClassLoader(),
                new Class<?>[] {type}, (self, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private MvcResult assertProblem(RequestBuilder request, int status, String code) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"");
        return result;
    }

    private MvcResult exchange(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        BODIES.assertExchange(result);
        return result;
    }

    /** Incident {@link #INC} at bank-a: post-to-core timed out; ops.lan proposes, ops.minh decides. */
    static final class FakeIncidents implements IncidentService {

        final List<IncidentQuery> queries = new ArrayList<>();
        final List<Map<String, Object>> outputs = new ArrayList<>();

        private static Incident open(long lock, int retries, boolean retrying, List<String> actions) {
            return new Incident(INC, "01JD0000000000000000000010", "post-to-core", "01JD0000000000000000000T01",
                    "ADAPTER_TIMEOUT", "Attempt 3 of 3 failed with ADAPTER_TIMEOUT; no retry is left", retries, "OPEN",
                    lock, NOW, null, null, null, null, retrying, actions);
        }

        private static PendingResolution proposal() {
            return new PendingResolution("ops.lan", NOW, "Limit raised by credit committee, memo CC-118",
                    Map.of("approvedLimit", Map.of("amount", "30000000", "currency", "VND")), "four-eyes");
        }

        @Override
        public List<Incident> openIncidents(String instanceId) {
            return List.of(open(0, 0, false, List.of("retry", "resolve")));
        }

        volatile long listLock = 0;
        volatile List<String> listActions = List.of("retry", "resolve");

        @Override
        public Page<Incident> incidents(IncidentQuery query) {
            queries.add(query);
            return new Page<>(List.of(open(listLock, 0, false, listActions)), null);
        }

        @Override
        public Incident incident(String incidentId) {
            if (!INC.equals(incidentId)) {
                throw new IncidentNotFoundException("No incident " + incidentId + " in this tenant");
            }
            return open(0, 0, false, List.of("retry", "resolve"));
        }

        @Override
        public Incident retry(String incidentId, long expectedLockVersion, String reason, String idempotencyKey) {
            if (expectedLockVersion == 9) {
                throw new InvalidStateException("Incident " + incidentId + " is RESOLVED");
            }
            return open(1, 1, true, List.of());
        }

        @Override
        public Incident resolve(String incidentId, long expectedLockVersion, Map<String, Object> output, String reason,
                String idempotencyKey) {
            if (!(output.get("approvedLimit") instanceof Map)) {
                throw new OutputInvalidException("The output breaks the step's output schema",
                        List.of("$.approvedLimit: expected object"));
            }
            outputs.add(output);
            return new Incident(INC, "01JD0000000000000000000010", "post-to-core", "01JD0000000000000000000T01",
                    "ADAPTER_TIMEOUT", "Attempt 3 of 3 failed", 0, "RESOLUTION_PENDING", 1, NOW, null, null, null,
                    proposal(), false, List.of());
        }

        @Override
        public Incident approveResolution(String incidentId, long expectedLockVersion, String comment,
                String idempotencyKey) {
            if ("a-self".equals(idempotencyKey)) {
                throw new SelfApprovalForbiddenException("The proposer cannot approve it");
            }
            return new Incident(INC, "01JD0000000000000000000010", "post-to-core", "01JD0000000000000000000T01",
                    "ADAPTER_TIMEOUT", "Attempt 3 of 3 failed", 0, "RESOLVED", 2, NOW, NOW, "COMPLETED_MANUALLY",
                    "ops.minh", proposal(), false, List.of());
        }

        @Override
        public Incident rejectResolution(String incidentId, long expectedLockVersion, String reason,
                String idempotencyKey) {
            if ("j-read".equals(idempotencyKey)) {
                throw new PermissionDeniedException("Needs processes:incident:manage");
            }
            return open(2, 0, false, List.of("retry", "resolve"));
        }
    }
}

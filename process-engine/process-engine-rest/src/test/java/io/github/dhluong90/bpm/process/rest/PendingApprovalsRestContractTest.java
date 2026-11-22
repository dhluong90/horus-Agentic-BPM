package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PendingApproval;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.TaskService;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /approvals} (#31, PRE-190) as the controller and the problem handler write it, validated against
 * {@code process-engine/api/openapi.yaml}. The query service is a test double that answers like the engine; the
 * controller, the JSON views and the problem details are the real ones.
 */
class PendingApprovalsRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String URL = ProcessController.BASE + "/approvals";
    private static final String HASH = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b";

    private final List<PageRequest> requests = new ArrayList<>();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcessController(
            proxy(DefinitionService.class), proxy(ProcessEngine.class), proxy(TaskService.class), queries(), 1_000_000))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_190_the_page_matches_the_contract_and_carries_the_hash_and_the_actions_but_no_definition() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("limit", "20").param("cursor", "c1")).andReturn();

        BODIES.assertExchange(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"key\":\"kyc-onboarding\"").contains("\"version\":2")
                .contains("\"definitionHash\":\"" + HASH + "\"").contains("\"lockVersion\":1")
                .contains("\"requestedBy\":\"alice\"").contains("\"requestedAt\":\"2027-04-05T03:30:00Z\"")
                .contains("\"allowedActions\":[]")
                .contains("\"deniedActions\":{\"approve\":\"SELF_APPROVAL_FORBIDDEN\",\"reject\":\"SELF_APPROVAL_FORBIDDEN\"}")
                .contains("\"nextCursor\":\"next\"").contains("\"hasNext\":true")
                .doesNotContain("definitionJson").doesNotContain("sourceDocument");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.limit()).isEqualTo(20);
            assertThat(r.cursor()).isEqualTo("c1");
        });
    }

    @Test
    void PRE_190_a_bad_limit_a_forged_cursor_and_a_missing_permission_are_problems_of_the_contract() throws Exception {
        for (Object[] row : new Object[][] {{"limit", "many", 400, "QUERY_INVALID"}, {"cursor", "forged", 400, "QUERY_INVALID"},
                {"cursor", "denied", 403, "PERMISSION_DENIED"}}) {
            MvcResult result = mvc.perform(get(URL).param((String) row[0], (String) row[1])).andReturn();

            BODIES.assertExchange(result);
            assertThat(result.getResponse().getStatus()).isEqualTo(row[2]);
            assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + row[3] + "\"");
        }
    }

    private ProcessQueryService queries() {
        return (ProcessQueryService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ProcessQueryService.class}, (self, method, args) -> {
                    if (!method.getName().equals("pendingApprovals")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    PageRequest request = (PageRequest) args[0];
                    requests.add(request);
                    if ("forged".equals(request.cursor())) {
                        throw new QueryInvalidException("cursor was not issued by this list; pass nextCursor unchanged");
                    }
                    if ("denied".equals(request.cursor())) {
                        throw new PermissionDeniedException("Needs processes:read");
                    }
                    return new Page<>(List.of(new PendingApproval("kyc-onboarding", "KYC onboarding", 2, HASH, 1, "alice",
                            Instant.parse("2027-04-05T03:30:00Z"), List.of(),
                            denied())), "next");
                });
    }

    private static Map<String, String> denied() {
        Map<String, String> denied = new java.util.LinkedHashMap<>();
        denied.put("approve", "SELF_APPROVAL_FORBIDDEN");
        denied.put("reject", "SELF_APPROVAL_FORBIDDEN");
        return denied;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(PendingApprovalsRestContractTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
